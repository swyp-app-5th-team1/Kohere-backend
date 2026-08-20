/**
 * 매물 예약(신청) Bounded Context. {@code ACTIVE} 세입자가 방 상품(roomOffer)에 타겟 입주일·계약기간(개월수)으로 예약을 저장하고, 내
 * 예약을 목록·단건 상세로 조회한다. MVP의 예약은 "신청" 성격이라 중복 제한이 없다(예약은 세입자 전용).
 *
 * <p>도메인 에러 코드 prefix: {@code BOOKING}. 스펙: docs/api/specs/04-booking-inquiry-chat.md.
 *
 * <p>조회 시점에 매물 요약·가격은 {@code listing :: api}, 예약자 성명은 {@code user :: api}로 실시간 조인한다(스냅샷 없음,
 * cross-store 조인 금지 · ADR-0002/0005). 예약 생성 시 {@code BookingCreatedEvent}를 같은 MySQL 커밋에 기록하며, chat
 * 모듈이 이를 비동기로 처리해 동일 매물 채팅방과 BOOKING_CARD를 보장한다. booking은 chat 구현을 직접 참조하지 않는다.
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "Booking",
    allowedDependencies = {"common", "listing :: api", "user :: api"})
package com.kohere.booking;
