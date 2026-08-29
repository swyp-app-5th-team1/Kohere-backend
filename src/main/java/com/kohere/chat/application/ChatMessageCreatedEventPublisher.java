package com.kohere.chat.application;

import com.kohere.chat.ChatMessageCreatedEvent;
import com.kohere.chat.ChatMessageKind;
import com.kohere.chat.domain.ChatRoom;
import com.kohere.chat.domain.Message;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * chat 내부 저장 결과를 다른 모듈이 사용할 수 있는 {@link ChatMessageCreatedEvent}로 변환해 발행한다.
 *
 * <p>호출자는 반드시 메시지 저장 트랜잭션 안에서 이 컴포넌트를 사용한다. 그러면 Spring Modulith가 메시지와 이벤트 publication을 같은 트랜잭션에
 * 기록하고, notification listener는 커밋이 끝난 뒤에만 실행된다.
 */
@Component
@RequiredArgsConstructor
public class ChatMessageCreatedEventPublisher {

  private final ApplicationEventPublisher eventPublisher;

  /**
   * 새로 저장된 메시지와 채팅방 사본으로 수신자용 이벤트 한 건을 발행한다.
   *
   * @param room 원인 메시지가 속한 채팅방
   * @param message DB ID가 확정된 신규 메시지
   * @param recipientUserId 푸시를 받을 상대 사용자 ID
   */
  public void publish(ChatRoom room, Message message, long recipientUserId) {
    Objects.requireNonNull(room, "room is required");
    Objects.requireNonNull(message, "message is required");
    Long roomId = Objects.requireNonNull(room.getId(), "roomId is required");
    Long messageId = Objects.requireNonNull(message.getId(), "messageId is required");
    if (!roomId.equals(message.getChatRoomId())) {
      throw new IllegalArgumentException("message must belong to the published chat room");
    }

    eventPublisher.publishEvent(
        new ChatMessageCreatedEvent(
            UUID.randomUUID(),
            ChatMessageKind.valueOf(message.getType().name()),
            roomId,
            messageId,
            recipientUserId,
            room.getListingId(),
            Objects.requireNonNull(room.getListingSnapshot(), "listingSnapshot is required")
                .title(),
            message.getSentAt()));
  }
}
