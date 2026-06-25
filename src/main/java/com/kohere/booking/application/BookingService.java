package com.kohere.booking.application;

import com.kohere.booking.application.dto.BookingResponse;
import com.kohere.booking.domain.BookingRepository;
import com.kohere.booking.presentation.dto.BookingRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * 매물 신청(예약) 유스케이스 조율. 도메인(포트)을 호출하고 흐름만 조율한다. 도메인 규칙은 엔티티/도메인 서비스에 둔다
 * (docs/convention/code-style.md §3-3).
 *
 * <p>의존성은 생성자 주입({@code @RequiredArgsConstructor})으로 받는다(§3-4). 인증 주체(세입자 tenantId)는
 * SecurityContext에서 가져온다(TODO: 보안 설정 후 연동).
 *
 * <p>모듈 간 통신은 도메인 이벤트 기반이다(ADR-0002). 예약 생성 후 임대인과의 채팅방 보장·예약 카드(BOOKING_CARD) 고정·푸시 알림은 chat 모듈의
 * 책임이며, {@link com.kohere.booking.BookingCreatedEvent}를 발행해 위임한다(chat 타입 직접 import 금지, 역의존 없음).
 *
 * <p>TODO: 영속 계층(JPA) 도입 시 유스케이스에 트랜잭션 경계({@code @Transactional})를 추가한다.
 */
@Service
@RequiredArgsConstructor
public class BookingService {

  private final BookingRepository bookingRepository;
  private final ApplicationEventPublisher eventPublisher;

  public BookingResponse createBooking(String listingId, BookingRequest request) {
    // TODO: 본인 매물·중복 신청·입주 희망일 검증 후 예약 저장.
    // 저장 성공 후 BookingCreatedEvent를 발행하면 chat 모듈이 채팅방·예약 카드·푸시 알림을 처리한다(ADR-0002).
    // 예: eventPublisher.publishEvent(
    //         new BookingCreatedEvent(
    //             bookingId, listingId, tenantId, request.moveInDate(),
    // request.contractPeriod().name()));
    throw new UnsupportedOperationException("TODO: 매물 신청(예약 생성) — 검증·저장 후 BookingCreatedEvent 발행");
  }
}
