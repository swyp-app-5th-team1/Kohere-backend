package com.kohere.chat.infrastructure.websocket;

import com.kohere.chat.application.TextMessageSaveResult;
import com.kohere.chat.domain.Message;
import com.kohere.chat.presentation.stomp.ChatStompDestinations;
import com.kohere.chat.presentation.stomp.dto.ChatMessageAckPayload;
import com.kohere.chat.presentation.stomp.dto.ChatMessageCreatedPayload;
import com.kohere.chat.presentation.stomp.dto.ChatRoomEventPayload;
import com.kohere.chat.presentation.stomp.dto.ChatStompEventType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * 커밋된 TEXT 저장 결과를 Simple Broker의 room topic과 개인 queue로 전달한다.
 *
 * <p>이 컴포넌트는 DB를 수정하지 않는다. broker 전송에 실패해도 이미 커밋된 원문은 MySQL에 남고, 앱은 재연결 뒤 REST 이력으로 복구한다. 로그에는 원문을
 * 남기지 않고 roomId·messageId·userId만 기록한다.
 */
@Slf4j
@Component
public class ChatRealtimeMessagePublisher {

  private static final int PAYLOAD_VERSION = 1;

  private final ObjectProvider<SimpMessagingTemplate> messagingTemplateProvider;
  private final ChatSessionMessageSender sessionMessageSender;

  public ChatRealtimeMessagePublisher(
      ObjectProvider<SimpMessagingTemplate> messagingTemplateProvider,
      ChatSessionMessageSender sessionMessageSender) {
    this.messagingTemplateProvider = messagingTemplateProvider;
    this.sessionMessageSender = sessionMessageSender;
  }

  /** 신규 메시지만 room topic과 목록 갱신 queue로 발행하고, 모든 성공 결과에는 원래 session ACK를 보낸다. */
  public void publishTextResult(String senderSessionId, TextMessageSaveResult result) {
    Message message = result.message();

    if (!result.duplicate()) {
      publishRoomMessage(message);
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

  /** 두 참여자가 공통으로 병합할 수 있는 원문 저장 완료 이벤트를 room topic에 보낸다. */
  private void publishRoomMessage(Message message) {
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
            null,
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
