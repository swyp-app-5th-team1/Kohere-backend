package com.kohere.chat.infrastructure.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.kohere.chat.application.BookingCardService;
import com.kohere.chat.application.BookingCardWriter;
import com.kohere.chat.application.TextMessageSaveResult;
import com.kohere.chat.domain.BookingCardPayload;
import com.kohere.chat.domain.Message;
import com.kohere.chat.domain.MessageType;
import com.kohere.chat.presentation.stomp.ChatStompDestinations;
import com.kohere.chat.presentation.stomp.dto.ChatMessageAckPayload;
import com.kohere.chat.presentation.stomp.dto.ChatMessageCreatedPayload;
import com.kohere.chat.presentation.stomp.dto.ChatRoomEventPayload;
import com.kohere.chat.presentation.stomp.dto.ChatStompEventType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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

  /** 서버가 저장한 신청 카드는 원문 TEXT와 같은 시간축으로 발행하되 카드 payload를 별도 필드에 담는다. */
  @Test
  @DisplayName("신규 BOOKING_CARD는 room 메시지와 참여자별 목록 이벤트를 발행한다")
  void publishesBookingCardAndParticipantRoomEvents() {
    Message card = bookingCardMessage();
    BookingCardService.ProcessResult result =
        new BookingCardService.ProcessResult(
            card.getChatRoomId(),
            false,
            card,
            true,
            List.of(
                new BookingCardWriter.MemberActivityResult(SENDER_ID, true, false),
                new BookingCardWriter.MemberActivityResult(RECIPIENT_ID, true, true)));

    publisher.publishNewCard(result);

    ArgumentCaptor<ChatMessageCreatedPayload> roomPayload =
        ArgumentCaptor.forClass(ChatMessageCreatedPayload.class);
    verify(messagingTemplate)
        .convertAndSend(eq(ChatStompDestinations.roomTopic(10L)), roomPayload.capture());

    ArgumentCaptor<ChatRoomEventPayload> senderEvent =
        ArgumentCaptor.forClass(ChatRoomEventPayload.class);
    verify(messagingTemplate)
        .convertAndSendToUser(
            eq(String.valueOf(SENDER_ID)),
            eq(ChatStompDestinations.ROOM_EVENT_USER_DESTINATION),
            senderEvent.capture());

    ArgumentCaptor<ChatRoomEventPayload> recipientEvent =
        ArgumentCaptor.forClass(ChatRoomEventPayload.class);
    verify(messagingTemplate)
        .convertAndSendToUser(
            eq(String.valueOf(RECIPIENT_ID)),
            eq(ChatStompDestinations.ROOM_EVENT_USER_DESTINATION),
            recipientEvent.capture());

    ChatMessageCreatedPayload payload = roomPayload.getValue();
    assertThat(payload.type()).isEqualTo(MessageType.BOOKING_CARD);
    assertThat(payload.clientMessageId()).isNull();
    assertThat(payload.senderId()).isNull();
    assertThat(payload.originalContent()).isNull();
    assertThat(payload.bookingCard().bookingId()).isEqualTo(9001L);
    assertThat(payload.bookingCard().listing().thumbnailUrl())
        .isEqualTo("https://cdn.kohere.com/room.jpg");
    assertThat(senderEvent.getValue().eventType()).isEqualTo(ChatStompEventType.ROOM_UPDATED);
    assertThat(recipientEvent.getValue().eventType()).isEqualTo(ChatStompEventType.ROOM_REOPENED);
    verifyNoInteractions(sessionMessageSender);
  }

  /** 신청으로 처음 생긴 채팅방은 두 참여자 목록에 새 항목으로 추가되므로 ROOM_CREATED를 보낸다. */
  @Test
  @DisplayName("신청이 새 채팅방을 만들면 참여자에게 ROOM_CREATED를 발행한다")
  void publishesRoomCreatedForNewConversation() {
    Message card = bookingCardMessage();
    BookingCardService.ProcessResult result =
        new BookingCardService.ProcessResult(
            card.getChatRoomId(),
            true,
            card,
            true,
            List.of(
                new BookingCardWriter.MemberActivityResult(SENDER_ID, true, false),
                new BookingCardWriter.MemberActivityResult(RECIPIENT_ID, true, false)));

    publisher.publishNewCard(result);

    ArgumentCaptor<ChatRoomEventPayload> senderEvent =
        ArgumentCaptor.forClass(ChatRoomEventPayload.class);
    verify(messagingTemplate)
        .convertAndSendToUser(
            eq(String.valueOf(SENDER_ID)),
            eq(ChatStompDestinations.ROOM_EVENT_USER_DESTINATION),
            senderEvent.capture());
    assertThat(senderEvent.getValue().eventType()).isEqualTo(ChatStompEventType.ROOM_CREATED);
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

  /** MySQL에 저장이 끝난 서버 생성 BOOKING_CARD fixture다. */
  private static Message bookingCardMessage() {
    BookingCardPayload payload =
        new BookingCardPayload(
            9001L,
            new BookingCardPayload.Listing(
                "listing-1",
                "https://cdn.kohere.com/room.jpg",
                "Hongdae Studio share",
                "Seogyo-dong, Mapo-gu",
                420_000),
            new BookingCardPayload.Applicant(
                SENDER_ID, "Gil dong Hong", "MALE", "MN", "Mongolia", "tenant@example.com"),
            "offer-1",
            "Room A",
            LocalDate.of(2026, 6, 15),
            3,
            0,
            1_260_000);

    return Message.builder()
        .id(601L)
        .chatRoomId(10L)
        .type(MessageType.BOOKING_CARD)
        .bookingId(payload.bookingId())
        .payload(payload)
        .sentAt(Instant.parse("2026-08-21T10:16:30Z"))
        .build();
  }
}
