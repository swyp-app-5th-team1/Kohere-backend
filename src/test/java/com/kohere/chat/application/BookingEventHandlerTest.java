package com.kohere.chat.application;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.kohere.booking.BookingCreatedEvent;
import com.kohere.chat.domain.BookingCardPayload;
import com.kohere.chat.domain.Message;
import com.kohere.chat.domain.MessageType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 예약 이벤트 처리 결과가 신규 카드일 때만 실시간 발행 포트로 넘어가는지 검증한다. */
class BookingEventHandlerTest {

  private final BookingCardService bookingCardService = mock(BookingCardService.class);
  private final BookingCardRealtimePublisher realtimePublisher =
      mock(BookingCardRealtimePublisher.class);

  private BookingEventHandler handler;

  @BeforeEach
  void setUp() {
    handler = new BookingEventHandler(bookingCardService, realtimePublisher);
  }

  /** 카드 저장 트랜잭션이 끝난 결과를 받은 뒤에만 실시간 발행을 요청한다. */
  @Test
  @DisplayName("신규 BOOKING_CARD 저장 결과는 실시간 발행기로 전달한다")
  void publishesNewCard() {
    BookingCreatedEvent event = event();
    BookingCardService.ProcessResult result = processResult(true);
    given(bookingCardService.process(event)).willReturn(result);

    handler.onBookingCreated(event);

    verify(realtimePublisher).publishNewCard(result);
  }

  /** 영속 이벤트가 재전달돼도 기존 카드를 다시 방송하면 앱에 같은 카드가 두 번 보이므로 발행하지 않는다. */
  @Test
  @DisplayName("중복 BOOKING_CARD 결과는 실시간으로 재발행하지 않는다")
  void doesNotRepublishDuplicateCard() {
    BookingCreatedEvent event = event();
    given(bookingCardService.process(event)).willReturn(processResult(false));

    handler.onBookingCreated(event);

    verifyNoInteractions(realtimePublisher);
  }

  /** listener 로그에 필요한 식별자만 가진 최소 예약 이벤트 fixture다. */
  private static BookingCreatedEvent event() {
    return mock(BookingCreatedEvent.class);
  }

  /** 신규·중복 분기에서 함께 사용하는 저장 결과 fixture다. */
  private static BookingCardService.ProcessResult processResult(boolean created) {
    BookingCardPayload payload =
        new BookingCardPayload(
            9001L,
            new BookingCardPayload.Listing("listing-1", null, "Hongdae Studio", "Mapo-gu", 420_000),
            new BookingCardPayload.Applicant(
                42L, "Tenant", "MALE", "MN", "Mongolia", "tenant@example.com"),
            "offer-1",
            "Room A",
            LocalDate.of(2026, 6, 15),
            3,
            0,
            1_260_000);
    Message message =
        Message.builder()
            .id(501L)
            .chatRoomId(10L)
            .type(MessageType.BOOKING_CARD)
            .bookingId(payload.bookingId())
            .payload(payload)
            .sentAt(Instant.parse("2026-08-21T10:15:30Z"))
            .build();

    return new BookingCardService.ProcessResult(
        10L,
        false,
        message,
        created,
        created
            ? List.of(
                new BookingCardWriter.MemberActivityResult(42L, true, false),
                new BookingCardWriter.MemberActivityResult(77L, true, false))
            : List.of());
  }
}
