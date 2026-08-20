package com.kohere.chat.infrastructure.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.kohere.chat.application.TextMessageSaveResult;
import com.kohere.chat.domain.Message;
import com.kohere.chat.domain.MessageType;
import com.kohere.chat.presentation.stomp.ChatStompDestinations;
import com.kohere.chat.presentation.stomp.dto.ChatMessageAckPayload;
import com.kohere.chat.presentation.stomp.dto.ChatMessageCreatedPayload;
import com.kohere.chat.presentation.stomp.dto.ChatRoomEventPayload;
import com.kohere.chat.presentation.stomp.dto.ChatStompEventType;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/** 커밋된 저장 결과가 신규·중복에 맞는 broker 이벤트로만 바뀌는지 빠르게 검증한다. */
class ChatRealtimeMessagePublisherTest {

  private static final String SESSION_ID = "sender-session";
  private static final long SENDER_ID = 42L;
  private static final long RECIPIENT_ID = 77L;

  private final SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
  private final ChatSessionMessageSender sessionMessageSender =
      mock(ChatSessionMessageSender.class);

  private ChatRealtimeMessagePublisher publisher;

  @BeforeEach
  void setUp() {
    @SuppressWarnings("unchecked")
    ObjectProvider<SimpMessagingTemplate> provider = mock(ObjectProvider.class);
    given(provider.getObject()).willReturn(messagingTemplate);
    publisher = new ChatRealtimeMessagePublisher(provider, sessionMessageSender);
  }

  /** 신규 원문은 방 참여자에게 발행하고 숨겼던 수신자에게 재표시 신호를 보내며 발신자에게 ACK한다. */
  @Test
  @DisplayName("신규 TEXT는 room 메시지·목록 이벤트·ACK를 발행한다")
  void publishesNewTextAndReopenEvent() {
    Message message = message();

    publisher.publishTextResult(
        SESSION_ID, new TextMessageSaveResult(message, false, RECIPIENT_ID, true));

    ArgumentCaptor<ChatMessageCreatedPayload> roomPayload =
        ArgumentCaptor.forClass(ChatMessageCreatedPayload.class);
    verify(messagingTemplate)
        .convertAndSend(eq(ChatStompDestinations.roomTopic(10L)), roomPayload.capture());
    verify(messagingTemplate)
        .convertAndSendToUser(
            eq(String.valueOf(SENDER_ID)),
            eq(ChatStompDestinations.ROOM_EVENT_USER_DESTINATION),
            any(ChatRoomEventPayload.class));

    ArgumentCaptor<ChatRoomEventPayload> recipientEvent =
        ArgumentCaptor.forClass(ChatRoomEventPayload.class);
    verify(messagingTemplate)
        .convertAndSendToUser(
            eq(String.valueOf(RECIPIENT_ID)),
            eq(ChatStompDestinations.ROOM_EVENT_USER_DESTINATION),
            recipientEvent.capture());
    verify(sessionMessageSender)
        .sendToSession(
            eq(SESSION_ID),
            eq(ChatStompDestinations.ACK_USER_DESTINATION),
            any(ChatMessageAckPayload.class));

    assertThat(roomPayload.getValue().originalContent()).isEqualTo("안녕하세요");
    assertThat(recipientEvent.getValue().eventType()).isEqualTo(ChatStompEventType.ROOM_REOPENED);
  }

  /** 중복 재전송은 이미 전달한 원문과 목록 갱신을 반복하지 않고 기존 저장 결과 ACK만 다시 보낸다. */
  @Test
  @DisplayName("중복 TEXT는 room 재방송 없이 duplicate ACK만 발행한다")
  void publishesOnlyAckForDuplicate() {
    publisher.publishTextResult(
        SESSION_ID, new TextMessageSaveResult(message(), true, RECIPIENT_ID, false));

    verifyNoInteractions(messagingTemplate);
    ArgumentCaptor<ChatMessageAckPayload> ack =
        ArgumentCaptor.forClass(ChatMessageAckPayload.class);
    verify(sessionMessageSender)
        .sendToSession(
            eq(SESSION_ID), eq(ChatStompDestinations.ACK_USER_DESTINATION), ack.capture());
    assertThat(ack.getValue().duplicate()).isTrue();
  }

  /** MySQL에서 이미 ID와 저장 시각을 받은 TEXT 정본 fixture다. */
  private static Message message() {
    return Message.builder()
        .id(501L)
        .chatRoomId(10L)
        .senderId(SENDER_ID)
        .type(MessageType.TEXT)
        .content("안녕하세요")
        .clientMessageId(UUID.fromString("b6506eb7-bf2d-47c8-a8d2-5f75cdb6d849"))
        .sentAt(Instant.parse("2026-08-21T10:15:30Z"))
        .build();
  }
}
