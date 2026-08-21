package com.kohere.chat.application;

import com.kohere.chat.application.dto.BookingCardResponse;
import com.kohere.chat.application.dto.MessageResponse;
import com.kohere.chat.application.dto.MessageTranslationResponse;
import com.kohere.chat.domain.ChatRoomMember;
import com.kohere.chat.domain.ChatRoomMemberRepository;
import com.kohere.chat.domain.ChatRoomNotFoundException;
import com.kohere.chat.domain.ChatRoomRepository;
import com.kohere.chat.domain.Message;
import com.kohere.chat.domain.MessageRepository;
import com.kohere.chat.domain.MessageType;
import com.kohere.chat.domain.translation.ChatMessageTranslation;
import com.kohere.chat.domain.translation.ChatMessageTranslationRepository;
import com.kohere.chat.domain.translation.TranslationResultStatus;
import com.kohere.common.exception.InvalidInputException;
import com.kohere.common.response.CursorResponse;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 채팅방 화면에 보여 줄 저장된 메시지를 조회하는 읽기 전용 서비스다.
 *
 * <p>메시지를 보내는 기능과 과거 메시지를 읽는 기능을 분리하면, 실시간 전송(STOMP)의 중복 방지 규칙과 REST 조회 규칙이 섞이지 않는다. 이 서비스는 MySQL에
 * 이미 저장된 메시지만 읽는다.
 *
 * <p>조회 방법은 두 가지다. {@code cursor}는 위로 스크롤할 때 더 오래된 메시지를 찾고, {@code afterMessageId}는 WebSocket 재연결 뒤
 * 놓친 새 메시지를 찾는다. 둘 다 없으면 채팅방의 최근 메시지부터 조회한다.
 */
@Service
@RequiredArgsConstructor
public class ChatMessageHistoryService {

  /** 한 번의 요청으로 읽을 수 있는 최대 메시지 수다. 지나치게 큰 조회가 DB와 응답을 오래 점유하지 않게 제한한다. */
  private static final int MAX_PAGE_SIZE = 100;

  private final ChatRoomRepository chatRoomRepository;
  private final ChatRoomMemberRepository memberRepository;
  private final MessageRepository messageRepository;
  private final ChatMessageTranslationRepository translationRepository;

  /**
   * 로그인 사용자에게 보이는 메시지를 커서 방식으로 반환한다.
   *
   * @param userId JWT에서 확인한 로그인 사용자의 {@code users.id}
   * @param roomId 앱이 채팅방 목록·단건 응답에서 받은 서버 채팅방 ID
   * @param cursor 이 ID보다 오래된 메시지를 찾는 기준. 첫 화면에서는 {@code null}
   * @param afterMessageId 이 ID보다 새 메시지를 찾는 기준. WebSocket 재연결 때 사용
   * @param size 한 번에 반환할 메시지 수(1~100)
   * @return 메시지 목록, 다음 커서, 다음 페이지 존재 여부
   */
  @Transactional(readOnly = true)
  public CursorResponse<MessageResponse> getMessages(
      long userId, long roomId, String cursor, String afterMessageId, int size) {
    validateRequest(cursor, afterMessageId, size);

    // 공유 메시지를 읽기 전에 사용자별 member 행부터 확인한다. 비참여자는 방의 존재 여부도 알 수 없다.
    ChatRoomMember member = visibleMember(roomId, userId);

    // no-FK 저장 정책에서는 member만 남고 room이 사라질 가능성을 DB가 직접 막지 못하므로 방 존재도 확인한다.
    chatRoomRepository.findById(roomId).orElseThrow(ChatRoomNotFoundException::new);

    Long beforeId = parseMessageId("cursor", cursor);
    Long afterId = parseMessageId("afterMessageId", afterMessageId);

    if (afterId != null) {
      return newerMessages(userId, roomId, member, afterId, size);
    }
    return olderMessages(userId, roomId, member, beforeId, size);
  }

  /** 잘못된 페이지 크기나 서로 충돌하는 조회 방식을 400 INVALID_INPUT으로 거부한다. */
  private static void validateRequest(String cursor, String afterMessageId, int size) {
    if (size < 1 || size > MAX_PAGE_SIZE) {
      throw new InvalidInputException("size", "validation.range", 1, MAX_PAGE_SIZE, size);
    }
    if (cursor != null && afterMessageId != null) {
      throw new InvalidInputException(
          "cursor", "cursor and afterMessageId cannot be used together");
    }
  }

  /** 참여 중이고 현재 사용자 화면에서 숨기지 않은 채팅방만 조회할 수 있게 한다. */
  private ChatRoomMember visibleMember(long roomId, long userId) {
    ChatRoomMember member =
        memberRepository
            .findByChatRoomIdAndUserId(roomId, userId)
            .orElseThrow(ChatRoomNotFoundException::new);

    // roomHiddenAt이 있으면 사용자가 삭제한 채팅방이다. 일반 사용자는 roomId를 알아도 직접 다시 열 수 없다.
    if (member.getRoomHiddenAt() != null) {
      throw new ChatRoomNotFoundException();
    }
    return member;
  }

  /** query 문자열을 양의 서버 messageId로 바꾼다. 값이 없으면 첫 페이지라는 뜻으로 null을 반환한다. */
  private static Long parseMessageId(String field, String value) {
    if (value == null) {
      return null;
    }

    try {
      long parsed = Long.parseLong(value);
      if (parsed < 1) {
        throw new NumberFormatException("messageId must be positive");
      }
      return parsed;
    } catch (NumberFormatException exception) {
      throw new InvalidInputException(field, "positive integer messageId required", value);
    }
  }

  /** 최근 메시지 또는 cursor보다 오래된 메시지를 최신순으로 반환한다. */
  private CursorResponse<MessageResponse> olderMessages(
      long userId, long roomId, ChatRoomMember member, Long beforeId, int requestedSize) {
    long hiddenThrough = member.getHistoryHiddenThroughMessageId();

    // cursor 자체가 삭제 경계 이하라면 더 과거에는 사용자에게 보여 줄 메시지가 없다.
    if (beforeId != null && beforeId <= hiddenThrough) {
      return emptyPage();
    }

    // 한 건을 더 읽어야 다음 페이지가 있는지 추가 COUNT 쿼리 없이 판단할 수 있다.
    List<Message> candidates =
        messageRepository.findBefore(roomId, beforeId, requestedSize + 1).stream()
            .filter(message -> message.getId() > hiddenThrough)
            .toList();

    return toPage(userId, candidates, requestedSize);
  }

  /** WebSocket이 끊긴 동안 저장된 새 메시지를 오래된 순으로 반환한다. */
  private CursorResponse<MessageResponse> newerMessages(
      long userId, long roomId, ChatRoomMember member, long afterId, int requestedSize) {
    // 삭제 이전 메시지는 재연결 조회에서도 되살리면 안 되므로 둘 중 더 큰 번호부터 조회한다.
    long visibleAfterId = Math.max(afterId, member.getHistoryHiddenThroughMessageId());
    List<Message> candidates =
        messageRepository.findAfter(roomId, visibleAfterId, requestedSize + 1);

    return toPage(userId, candidates, requestedSize);
  }

  /** size+1 조회 결과를 실제 응답 크기로 자르고 다음 요청에 사용할 커서를 계산한다. */
  private CursorResponse<MessageResponse> toPage(
      long userId, List<Message> candidates, int requestedSize) {
    boolean hasNext = candidates.size() > requestedSize;
    List<Message> pageMessages = candidates.stream().limit(requestedSize).toList();
    Map<Long, ChatMessageTranslation> translationsByMessageId =
        translationRepository
            .findByMessageIdsAndRecipientUserId(
                pageMessages.stream().map(Message::getId).toList(), userId)
            .stream()
            .filter(ChatMessageTranslation::isTerminal)
            .collect(
                Collectors.toMap(
                    ChatMessageTranslation::getMessageId,
                    Function.identity(),
                    (first, ignored) -> first));
    List<MessageResponse> content =
        pageMessages.stream()
            .map(
                message ->
                    toResponse(userId, message, translationsByMessageId.get(message.getId())))
            .toList();

    // 다음 페이지가 있을 때만 마지막 messageId를 보낸다. 앱은 이 문자열을 같은 query에 그대로 다시 사용한다.
    String nextCursor =
        hasNext ? String.valueOf(pageMessages.get(pageMessages.size() - 1).getId()) : null;
    return new CursorResponse<>(content, nextCursor, hasNext);
  }

  /** 조회 결과가 없을 때 모든 분기가 같은 빈 페이지 모양을 사용하게 한다. */
  private static CursorResponse<MessageResponse> emptyPage() {
    return new CursorResponse<>(List.of(), null, false);
  }

  /** 도메인 메시지를 프런트엔드가 TEXT와 BOOKING_CARD로 구분해 그릴 수 있는 응답으로 바꾼다. */
  private static MessageResponse toResponse(
      long userId, Message message, ChatMessageTranslation translation) {
    boolean mine =
        message.getType() == MessageType.TEXT && message.getSenderId().longValue() == userId;
    BookingCardResponse bookingCard =
        message.getType() == MessageType.BOOKING_CARD
            ? BookingCardResponseMapper.toResponse(message.getPayload())
            : null;

    return new MessageResponse(
        message.getId(),
        message.getClientMessageId(),
        message.getChatRoomId(),
        message.getSenderId(),
        mine,
        message.getType(),
        message.getContent(),
        bookingCard,
        toTranslationResponse(translation),
        message.getSentAt());
  }

  /** 내부 작업 상태는 노출하지 않고 완료된 사용자별 번역 결과만 REST DTO로 바꾼다. */
  private static MessageTranslationResponse toTranslationResponse(
      ChatMessageTranslation translation) {
    if (translation == null || !translation.isTerminal()) {
      return null;
    }
    return new MessageTranslationResponse(
        TranslationResultStatus.valueOf(translation.getStatus().name()),
        translation.getTranslatedContent(),
        translation.getDetectedSourceLanguage(),
        translation.getTargetLanguage(),
        translation.getProvider());
  }
}
