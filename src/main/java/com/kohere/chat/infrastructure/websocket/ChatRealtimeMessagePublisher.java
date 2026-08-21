package com.kohere.chat.infrastructure.websocket;

import com.kohere.chat.application.BookingCardRealtimePublisher;
import com.kohere.chat.application.BookingCardResponseMapper;
import com.kohere.chat.application.BookingCardService;
import com.kohere.chat.application.BookingCardWriter;
import com.kohere.chat.application.TextMessageSaveResult;
import com.kohere.chat.application.translation.ChatTranslationResultPublisher;
import com.kohere.chat.domain.ChatRoomMember;
import com.kohere.chat.domain.ChatRoomMemberRepository;
import com.kohere.chat.domain.Message;
import com.kohere.chat.domain.MessageType;
import com.kohere.chat.domain.translation.ChatMessageTranslation;
import com.kohere.chat.domain.translation.TranslationResultStatus;
import com.kohere.chat.presentation.stomp.ChatStompDestinations;
import com.kohere.chat.presentation.stomp.dto.ChatMessageAckPayload;
import com.kohere.chat.presentation.stomp.dto.ChatMessageCreatedPayload;
import com.kohere.chat.presentation.stomp.dto.ChatRoomEventPayload;
import com.kohere.chat.presentation.stomp.dto.ChatStompEventType;
import com.kohere.chat.presentation.stomp.dto.ChatTranslationPayload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * 커밋된 TEXT·BOOKING_CARD 결과를 용도에 맞는 Simple Broker 경로로 전달한다.
 *
 * <p>이 컴포넌트는 DB를 수정하지 않는다. broker 전송에 실패해도 이미 커밋된 원문은 MySQL에 남고, 앱은 재연결 뒤 REST 이력으로 복구한다. 로그에는 원문을
 * 남기지 않고 roomId·messageId·userId만 기록한다.
 */
@Slf4j
@Component
public class ChatRealtimeMessagePublisher
    implements BookingCardRealtimePublisher, ChatTranslationResultPublisher {

  private static final int PAYLOAD_VERSION = 1;

  private final ObjectProvider<SimpMessagingTemplate> messagingTemplateProvider;
  private final ChatSessionMessageSender sessionMessageSender;
  private final ChatRoomMemberRepository memberRepository;

  public ChatRealtimeMessagePublisher(
      ObjectProvider<SimpMessagingTemplate> messagingTemplateProvider,
      ChatSessionMessageSender sessionMessageSender,
      ChatRoomMemberRepository memberRepository) {
    this.messagingTemplateProvider = messagingTemplateProvider;
    this.sessionMessageSender = sessionMessageSender;
    this.memberRepository = memberRepository;
  }

  /**
   * 신규 TEXT의 목록 갱신 신호와 발신자 ACK를 보낸다.
   *
   * <p>TEXT 원문은 room topic으로 먼저 발행하지 않는다. 수신자는 번역 최종 상태가 저장된 뒤 {@link #publish(Message,
   * ChatMessageTranslation)}에서 원문과 번역본을 한 이벤트로 받는다.
   */
  public void publishTextResult(String senderSessionId, TextMessageSaveResult result) {
    Message message = result.message();

    if (!result.duplicate()) {
      publishRoomListEvent(message.getSenderId(), message, ChatStompEventType.ROOM_UPDATED);
      publishRoomListEvent(
          result.recipientUserId(),
          message,
          result.recipientRoomReopened()
              ? ChatStompEventType.ROOM_REOPENED
              : ChatStompEventType.ROOM_UPDATED);
    }

    // ACK는 room topic 성공 여부가 아니라 MySQL 저장 결과를 뜻하므로 별도로 시도한다.
    sendAck(senderSessionId, message, result.duplicate());
  }

  /** {@inheritDoc} */
  @Override
  public void publish(Message originalMessage, ChatMessageTranslation translation) {
    if (!translation.isTerminal()) {
      throw new IllegalArgumentException("최종 번역 상태만 실시간으로 전달할 수 있습니다.");
    }

    ChatRoomMember recipient =
        memberRepository
            .findByChatRoomIdAndUserId(
                originalMessage.getChatRoomId(), translation.getRecipientUserId())
            .orElse(null);
    if (recipient == null || recipient.getRoomHiddenAt() != null) {
      // 번역 처리 중 사용자가 방을 삭제했다면 늦은 이벤트로 숨긴 방을 화면에 다시 만들지 않는다.
      return;
    }

    ChatTranslationPayload payload =
        new ChatTranslationPayload(
            PAYLOAD_VERSION,
            ChatStompEventType.MESSAGE_TRANSLATION_UPDATED,
            originalMessage.getId(),
            originalMessage.getClientMessageId(),
            originalMessage.getChatRoomId(),
            originalMessage.getSenderId(),
            originalMessage.getContent(),
            TranslationResultStatus.valueOf(translation.getStatus().name()),
            translation.getDetectedSourceLanguage(),
            translation.getTargetLanguage(),
            translation.getTranslatedContent(),
            translation.getProvider(),
            originalMessage.getSentAt(),
            translation.getTranslatedAt());

    try {
      messagingTemplateProvider
          .getObject()
          .convertAndSendToUser(
              String.valueOf(translation.getRecipientUserId()),
              ChatStompDestinations.TRANSLATION_USER_DESTINATION,
              payload);
    } catch (RuntimeException failure) {
      // DB 최종 결과는 이미 커밋됐으므로 연결 복구 뒤 REST 이력에서 다시 읽을 수 있다.
      log.error(
          "Chat translation publish failed: userId={}, roomId={}, messageId={}, status={}",
          translation.getRecipientUserId(),
          originalMessage.getChatRoomId(),
          originalMessage.getId(),
          translation.getStatus(),
          failure);
    }
  }

  /**
   * 신청 이벤트로 새로 커밋된 BOOKING_CARD를 두 참여자의 room topic과 현재 보이는 채팅방 목록에 반영한다.
   *
   * <p>서버가 만든 카드에는 프런트 임시 말풍선이나 {@code clientMessageId}가 없으므로 ACK를 보내지 않는다. 중복 예약 이벤트는 {@link
   * com.kohere.chat.application.BookingEventHandler}에서 이 메서드 자체를 호출하지 않는다.
   */
  @Override
  public void publishNewCard(BookingCardService.ProcessResult result) {
    Message message = result.message();
    publishRoomMessage(message);

    for (BookingCardWriter.MemberActivityResult member : result.memberActivities()) {
      // 삭제 상태가 그대로 유지된 사용자는 목록에 방이 없으므로 갱신 신호도 보내지 않는다.
      if (!member.roomVisible()) {
        continue;
      }

      ChatStompEventType eventType = roomEventType(result.roomCreated(), member.roomReopened());
      publishRoomListEvent(member.userId(), message, eventType);
    }
  }

  /** 신규 방·숨긴 방 재표시·일반 목록 갱신 중 프런트가 처리할 한 종류를 선택한다. */
  private static ChatStompEventType roomEventType(boolean roomCreated, boolean roomReopened) {
    if (roomCreated) {
      return ChatStompEventType.ROOM_CREATED;
    }
    return roomReopened ? ChatStompEventType.ROOM_REOPENED : ChatStompEventType.ROOM_UPDATED;
  }

  /** 두 참여자에게 동일한 서버 생성 BOOKING_CARD 저장 완료 이벤트를 room topic으로 보낸다. */
  private void publishRoomMessage(Message message) {
    boolean bookingCard = message.getType() == MessageType.BOOKING_CARD;
    ChatMessageCreatedPayload payload =
        new ChatMessageCreatedPayload(
            PAYLOAD_VERSION,
            ChatStompEventType.MESSAGE_CREATED,
            message.getId(),
            message.getClientMessageId(),
            message.getChatRoomId(),
            message.getSenderId(),
            message.getType(),
            message.getContent(),
            bookingCard ? BookingCardResponseMapper.toResponse(message.getPayload()) : null,
            message.getSentAt());

    try {
      messagingTemplateProvider
          .getObject()
          .convertAndSend(ChatStompDestinations.roomTopic(message.getChatRoomId()), payload);
    } catch (RuntimeException failure) {
      log.error(
          "Chat room message publish failed: roomId={}, messageId={}",
          message.getChatRoomId(),
          message.getId(),
          failure);
    }
  }

  /** 채팅방 목록을 보고 있는 각 사용자에게 REST 목록 재조회가 필요하다는 가벼운 신호를 보낸다. */
  private void publishRoomListEvent(long userId, Message message, ChatStompEventType eventType) {
    ChatRoomEventPayload payload =
        new ChatRoomEventPayload(
            PAYLOAD_VERSION,
            eventType,
            message.getChatRoomId(),
            message.getId(),
            message.getSentAt());
    try {
      // userId 문자열은 CONNECT 때 설정한 Principal.name과 같아 그 사용자의 모든 활성 기기에 전달된다.
      messagingTemplateProvider
          .getObject()
          .convertAndSendToUser(
              String.valueOf(userId), ChatStompDestinations.ROOM_EVENT_USER_DESTINATION, payload);
    } catch (RuntimeException failure) {
      log.error(
          "Chat room list event publish failed: userId={}, roomId={}, messageId={}, eventType={}",
          userId,
          message.getChatRoomId(),
          message.getId(),
          eventType,
          failure);
    }
  }

  /** 발신 앱이 임시 말풍선을 서버 messageId와 합칠 수 있도록 원래 socket 한 곳에만 저장 결과를 보낸다. */
  private void sendAck(String senderSessionId, Message message, boolean duplicate) {
    try {
      sessionMessageSender.sendToSession(
          senderSessionId,
          ChatStompDestinations.ACK_USER_DESTINATION,
          new ChatMessageAckPayload(
              PAYLOAD_VERSION,
              message.getClientMessageId(),
              message.getId(),
              message.getSentAt(),
              duplicate));
    } catch (RuntimeException failure) {
      log.error(
          "Chat ACK publish failed: roomId={}, messageId={}",
          message.getChatRoomId(),
          message.getId(),
          failure);
    }
  }
}
