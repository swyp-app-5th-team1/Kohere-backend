package com.kohere.chat.application;

import com.kohere.booking.BookingCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;

/**
 * 예약 생성 이벤트를 구독해 동일한 1:1 채팅방과 BOOKING_CARD 저장으로 연결한다.
 *
 * <p>프런트가 호출하는 컨트롤러가 아니라 Spring Modulith가 예약 커밋 뒤 자동 실행하는 내부 listener다. 이벤트 publication은 MySQL에 먼저
 * 기록되므로 처리 실패 시 완료로 표시되지 않고, 서버 재기동 시 다시 전달된다. 실제 중복 방지는 방 UNIQUE와 bookingId 카드 UNIQUE가 담당한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingEventHandler {

  private final BookingCardService bookingCardService;
  private final BookingCardRealtimePublisher realtimePublisher;

  /**
   * 예약 이벤트를 비동기로 처리한다.
   *
   * <p>{@code NOT_SUPPORTED}로 listener 바깥 트랜잭션을 만들지 않는 이유는 방 동시 생성의 UNIQUE 충돌 트랜잭션이 완전히 롤백된 뒤 기존 방을
   * 다시 조회해야 하기 때문이다. 방 생성과 카드 저장은 각각 책임 컴포넌트의 명확한 트랜잭션에서 실행된다.
   */
  @ApplicationModuleListener(
      id = "chat.booking-card-on-booking-created",
      propagation = Propagation.NOT_SUPPORTED)
  public void onBookingCreated(BookingCreatedEvent event) {
    BookingCardService.ProcessResult result = bookingCardService.process(event);

    // cardWriter의 @Transactional 메서드가 반환된 뒤이므로 여기서는 MySQL 커밋이 끝난 상태다. 중복 이벤트에는 실시간 재발행을 하지 않는다.
    if (result.cardCreated()) {
      realtimePublisher.publishNewCard(result);
    }

    // 신청자 이름·이메일·카드 JSON은 로그에 남기지 않고 추적에 필요한 식별자와 중복 여부만 기록한다.
    log.info(
        "BookingCreatedEvent 처리 완료: eventId={}, bookingId={}, chatRoomId={}, roomCreated={}, cardCreated={}",
        event.eventId(),
        event.bookingId(),
        result.chatRoomId(),
        result.roomCreated(),
        result.cardCreated());
  }
}
