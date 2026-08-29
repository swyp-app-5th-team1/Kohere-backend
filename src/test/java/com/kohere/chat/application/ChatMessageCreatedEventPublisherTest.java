package com.kohere.chat.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.kohere.chat.ChatMessageCreatedEvent;
import com.kohere.chat.ChatMessageKind;
import com.kohere.chat.domain.BookingCardPayload;
import com.kohere.chat.domain.ChatCategory;
import com.kohere.chat.domain.ChatRoom;
import com.kohere.chat.domain.InquiryCardPayload;
import com.kohere.chat.domain.ListingSnapshot;
import com.kohere.chat.domain.Message;
import com.kohere.chat.domain.MessageType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/** 채팅 내부 세 메시지 타입이 동일한 공개 이벤트 계약으로 변환되는지 검증한다. */
@ExtendWith(MockitoExtension.class)
class ChatMessageCreatedEventPublisherTest {

  private static final long ROOM_ID = 10L;
  private static final long MESSAGE_ID = 501L;
  private static final long RECIPIENT_ID = 77L;
  private static final Instant SENT_AT = Instant.parse("2026-08-29T06:30:00Z");

  @Mock private ApplicationEventPublisher applicationEventPublisher;

  private ChatMessageCreatedEventPublisher publisher;

  /** 각 테스트가 실제 Spring event publisher 대신 캡처 가능한 공통 발행기를 사용하게 준비한다. */
  @BeforeEach
  void setUp() {
    publisher = new ChatMessageCreatedEventPublisher(applicationEventPublisher);
  }

  /** TEXT·문의 카드·신청 카드가 종류와 공통 식별자를 잃지 않고 공개 이벤트가 되는지 확인한다. */
  @ParameterizedTest
  @EnumSource(MessageType.class)
  @DisplayName("세 채팅 메시지 종류를 공통 생성 이벤트로 발행한다")
  void publishesEverySupportedMessageType(MessageType messageType) {
    Message message = message(messageType);

    publisher.publish(room(), message, RECIPIENT_ID);

    ArgumentCaptor<ChatMessageCreatedEvent> captor =
        ArgumentCaptor.forClass(ChatMessageCreatedEvent.class);
    verify(applicationEventPublisher).publishEvent(captor.capture());
    ChatMessageCreatedEvent event = captor.getValue();
    assertThat(event.eventId()).isNotNull();
    assertThat(event.messageType()).isEqualTo(ChatMessageKind.valueOf(messageType.name()));
    assertThat(event.roomId()).isEqualTo(ROOM_ID);
    assertThat(event.messageId()).isEqualTo(MESSAGE_ID);
    assertThat(event.recipientUserId()).isEqualTo(RECIPIENT_ID);
    assertThat(event.listingId()).isEqualTo("listing-1");
    assertThat(event.listingTitle()).isEqualTo("고시원3");
    assertThat(event.sentAt()).isEqualTo(SENT_AT);
  }

  /** 메시지 종류별 필수 필드를 갖춘 저장 완료 fixture를 만든다. */
  private static Message message(MessageType messageType) {
    return switch (messageType) {
      case TEXT ->
          Message.builder()
              .id(MESSAGE_ID)
              .chatRoomId(ROOM_ID)
              .senderId(42L)
              .type(MessageType.TEXT)
              .content("안녕하세요")
              .clientMessageId(UUID.fromString("b6506eb7-bf2d-47c8-a8d2-5f75cdb6d849"))
              .sentAt(SENT_AT)
              .build();
      case INQUIRY_CARD ->
          Message.builder()
              .id(MESSAGE_ID)
              .chatRoomId(ROOM_ID)
              .type(MessageType.INQUIRY_CARD)
              .inquiryPayload(
                  new InquiryCardPayload(
                      "listing-1", null, "고시원3", "SEOUL", "MAPO_GU", "GOSHIWON", 300_000, 400_000))
              .sentAt(SENT_AT)
              .build();
      case BOOKING_CARD ->
          Message.builder()
              .id(MESSAGE_ID)
              .chatRoomId(ROOM_ID)
              .type(MessageType.BOOKING_CARD)
              .bookingId(9001L)
              .payload(bookingCardPayload())
              .sentAt(SENT_AT)
              .build();
    };
  }

  /** 신청 카드 메시지의 도메인 불변식을 만족하는 최소 payload를 만든다. */
  private static BookingCardPayload bookingCardPayload() {
    return new BookingCardPayload(
        9001L,
        new BookingCardPayload.Listing("listing-1", null, "고시원3", "마포구", 400_000),
        new BookingCardPayload.Applicant(
            42L, "Applicant", "MALE", "KR", "대한민국", "applicant@example.com"),
        "offer-1",
        "Room A",
        LocalDate.of(2030, 1, 1),
        3,
        0,
        1_200_000);
  }

  /** 이벤트에 사용할 매물 식별자와 생성 시점 제목을 가진 채팅방 fixture다. */
  private static ChatRoom room() {
    return ChatRoom.builder()
        .id(ROOM_ID)
        .listingId("listing-1")
        .tenantId(42L)
        .landlordId(RECIPIENT_ID)
        .category(ChatCategory.LANDLORD)
        .listingSnapshot(new ListingSnapshot("고시원3", "마포구"))
        .createdAt(SENT_AT.minusSeconds(60))
        .updatedAt(SENT_AT)
        .build();
  }
}
