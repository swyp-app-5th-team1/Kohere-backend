package com.kohere.booking;

import java.time.LocalDate;

/**
 * 예약 생성 도메인 이벤트. booking 모듈이 발행하고 다른 모듈(chat 등)이 구독한다(모듈 간 통신 규약: ADR-0002).
 *
 * <p>모듈이 발행하는 이벤트는 모듈 base 패키지(공개 API)에 둔다. 페이로드는 원시/공유 타입만 담아 발행 모듈의 internal 타입(예: {@code
 * booking.domain.ContractPeriod} enum)을 노출하지 않는다 — 그래서 {@code contractPeriod}는 enum이 아닌 문자열로 전달한다.
 */
public record BookingCreatedEvent(
    Long bookingId, String listingId, Long tenantId, LocalDate moveInDate, String contractPeriod) {}
