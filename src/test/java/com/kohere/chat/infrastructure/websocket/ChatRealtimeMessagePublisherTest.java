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
import com.kohere.chat.domain.ChatCategory;
import com.kohere.chat.domain.ChatParticipantRole;
import com.kohere.chat.domain.ChatRoom;
import com.kohere.chat.domain.ChatRoomMember;
import com.kohere.chat.domain.ChatRoomMemberRepository;
import com.kohere.chat.domain.InquiryCardPayload;
import com.kohere.chat.domain.ListingSnapshot;
import com.kohere.chat.domain.Message;
import com.kohere.chat.domain.MessageType;
import com.kohere.chat.domain.TranslationProvider;
import com.kohere.chat.domain.translation.ChatMessageTranslation;
import com.kohere.chat.domain.translation.ChatTranslationStatus;
import com.kohere.chat.domain.translation.TranslationResultStatus;
import com.kohere.chat.presentation.stomp.ChatStompDestinations;
import com.kohere.chat.presentation.stomp.dto.ChatMessageAckPayload;
import com.kohere.chat.presentation.stomp.dto.ChatMessageCreatedPayload;
import com.kohere.chat.presentation.stomp.dto.ChatRoomEventPayload;
import com.kohere.chat.presentation.stomp.dto.ChatStompEventType;
import com.kohere.chat.presentation.stomp.dto.ChatTranslationPayload;
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
  private final ChatRoomMemberRepository memberRepository = mock(ChatRoomMemberRepository.class);

  private ChatRealtimeMessagePublisher publisher;

  @BeforeEach
  void setUp() {
    @SuppressWarnings("unchecked")
    ObjectProvider<SimpMessagingTemplate> provider = mock(ObjectProvider.class);
    given(provider.getObject()).willReturn(messagingTemplate);
    publisher = new ChatRealtimeMessagePublisher(provider, sessionMessageSender, memberRepository);
  }

  /** 신규 TEXT는 원문을 먼저 방송하지 않고 목록 신호와 발신자 ACK만 즉시 보낸다. */
  @Test
  @DisplayName("신규 TEXT는 원문 선발행 없이 목록 이벤트와 ACK를 발행한다")
  void publishesNewTextAndReopenEvent() {
    Message message = message();

    publisher.publishTextResult(
        SESSION_ID, new TextMessageSaveResult(message, false, RECIPIENT_ID, true, 801L));
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

    assertThat(recipientEvent.getValue().eventType()).isEqualTo(ChatStompEventType.ROOM_REOPENED);
  }

  /** 번역 최종 상태가 커밋되면 수신자는 개인 queue 이벤트 하나에서 원문과 번역본을 함께 받는다. */
  @Test
  @DisplayName("번역 성공은 원문과 번역본을 수신자 개인 queue로 함께 보낸다")
  void publishesOriginalAndTranslationTogetherToRecipient() {
    Message original = message();
    ChatMessageTranslation translation = succeededTranslation(original.getId());
    given(memberRepository.findByChatRoomIdAndUserId(10L, RECIPIENT_ID))
        .willReturn(java.util.Optional.of(visibleRecipient()));

    publisher.publish(original, translation);

    ArgumentCaptor<ChatTranslationPayload> payload =
        ArgumentCaptor.forClass(ChatTranslationPayload.class);
    verify(messagingTemplate)
        .convertAndSendToUser(
            eq(String.valueOf(RECIPIENT_ID)),
            eq(ChatStompDestinations.TRANSLATION_USER_DESTINATION),
            payload.capture());
    assertThat(payload.getValue().originalContent()).isEqualTo("안녕하세요");
    assertThat(payload.getValue().translatedContent()).isEqualTo("Hello");
    assertThat(payload.getValue().status()).isEqualTo(TranslationResultStatus.SUCCEEDED);
    assertThat(payload.getValue().senderId()).isEqualTo(SENDER_ID);
  }

  /** 중복 재전송은 이미 전달한 원문과 목록 갱신을 반복하지 않고 기존 저장 결과 ACK만 다시 보낸다. */
  @Test
  @DisplayName("중복 TEXT는 room 재방송 없이 duplicate ACK만 발행한다")
  void publishesOnlyAckForDuplicate() {
    publisher.publishTextResult(
        SESSION_ID, new TextMessageSaveResult(message(), true, RECIPIENT_ID, false, null));

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

  /** 새 문의 방의 INQUIRY_CARD는 room topic과 두 참여자의 ROOM_CREATED 목록 신호로 전달한다. */
  @Test
  @DisplayName("신규 INQUIRY_CARD는 문의서와 ROOM_CREATED를 발행한다")
  void publishesInquiryCardAndRoomCreatedEvents() {
    ChatRoom room = inquiryRoom();
    Message card = inquiryCardMessage();

    publisher.publishNewInquiryCard(room, card);

    ArgumentCaptor<ChatMessageCreatedPayload> roomPayload =
        ArgumentCaptor.forClass(ChatMessageCreatedPayload.class);
    verify(messagingTemplate)
        .convertAndSend(eq(ChatStompDestinations.roomTopic(10L)), roomPayload.capture());

    ArgumentCaptor<ChatRoomEventPayload> tenantEvent =
        ArgumentCaptor.forClass(ChatRoomEventPayload.class);
    verify(messagingTemplate)
        .convertAndSendToUser(
            eq(String.valueOf(SENDER_ID)),
            eq(ChatStompDestinations.ROOM_EVENT_USER_DESTINATION),
            tenantEvent.capture());
    ArgumentCaptor<ChatRoomEventPayload> landlordEvent =
        ArgumentCaptor.forClass(ChatRoomEventPayload.class);
    verify(messagingTemplate)
        .convertAndSendToUser(
            eq(String.valueOf(RECIPIENT_ID)),
            eq(ChatStompDestinations.ROOM_EVENT_USER_DESTINATION),
            landlordEvent.capture());

    ChatMessageCreatedPayload payload = roomPayload.getValue();
    assertThat(payload.type()).isEqualTo(MessageType.INQUIRY_CARD);
    assertThat(payload.originalContent()).isNull();
    assertThat(payload.bookingCard()).isNull();
    assertThat(payload.inquiryCard().listingId()).isEqualTo("listing-1");
    assertThat(payload.inquiryCard().listingType()).isEqualTo("CO_LIVING");
    assertThat(payload.inquiryCard().monthlyRentMin()).isEqualTo(350_000);
    assertThat(payload.inquiryCard().monthlyRentMax()).isEqualTo(500_000);
    assertThat(tenantEvent.getValue().eventType()).isEqualTo(ChatStompEventType.ROOM_CREATED);
    assertThat(landlordEvent.getValue().eventType()).isEqualTo(ChatStompEventType.ROOM_CREATED);
    verifyNoInteractions(sessionMessageSender);
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

  /** 신규 문의서 실시간 발행에 사용하는 채팅방 fixture다. */
  private static ChatRoom inquiryRoom() {
    Instant now = Instant.parse("2026-08-21T10:14:30Z");
    return ChatRoom.builder()
        .id(10L)
        .listingId("listing-1")
        .tenantId(SENDER_ID)
        .landlordId(RECIPIENT_ID)
        .category(ChatCategory.LANDLORD)
        .listingSnapshot(new ListingSnapshot("Hongdae Studio share", "Seogyo-dong, Mapo-gu"))
        .lastMessageId(600L)
        .lastMessageAt(now)
        .createdAt(now)
        .updatedAt(now)
        .build();
  }

  /** MySQL에 저장이 끝난 서버 생성 INQUIRY_CARD fixture다. */
  private static Message inquiryCardMessage() {
    InquiryCardPayload payload =
        new InquiryCardPayload(
            "listing-1",
            "https://cdn.kohere.com/inquiry.jpg",
            "Hongdae Studio share",
            "SEOUL",
            "MAPO_GU",
            "CO_LIVING",
            350_000,
            500_000);
    return Message.builder()
        .id(600L)
        .chatRoomId(10L)
        .type(MessageType.INQUIRY_CARD)
        .inquiryPayload(payload)
        .sentAt(Instant.parse("2026-08-21T10:14:30Z"))
        .build();
  }

  /** 번역 이벤트를 받을 수 있는 현재 표시 상태의 수신자 fixture다. */
  private static ChatRoomMember visibleRecipient() {
    Instant now = Instant.parse("2026-08-21T10:15:30Z");
    return ChatRoomMember.builder()
        .id(2L)
        .chatRoomId(10L)
        .userId(RECIPIENT_ID)
        .counterpartId(SENDER_ID)
        .role(ChatParticipantRole.LANDLORD)
        .historyHiddenThroughMessageId(0L)
        .createdAt(now)
        .updatedAt(now)
        .build();
  }

  /** Google 번역 성공이 DB에 커밋된 상태 fixture다. */
  private static ChatMessageTranslation succeededTranslation(long messageId) {
    Instant now = Instant.parse("2026-08-21T10:15:31Z");
    return ChatMessageTranslation.builder()
        .id(801L)
        .messageId(messageId)
        .recipientUserId(RECIPIENT_ID)
        .targetLanguage("en")
        .detectedSourceLanguage("ko")
        .status(ChatTranslationStatus.SUCCEEDED)
        .translatedContent("Hello")
        .provider(TranslationProvider.GOOGLE_CLOUD_TRANSLATION)
        .model("NMT")
        .attemptCount(1)
        .translatedAt(now)
        .createdAt(now.minusSeconds(1))
        .updatedAt(now)
        .build();
  }
}
