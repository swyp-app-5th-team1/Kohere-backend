package com.kohere.booking.application.dto;

import com.kohere.booking.domain.BookingStatus;
import com.kohere.booking.domain.ContractPeriod;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 예약 생성 결과 DTO. 응용 계층이 도메인을 표현 계층으로 전달할 때 쓰는 결과 타입이다(표현 계층은 이를 공통 래퍼로 감싼다).
 *
 * <p>docs/api/specs/04-booking-inquiry-chat.md 신청(예약 생성) 응답 스키마. {@code chatRoomId}는 chat 모듈이 보장한
 * 채팅방 ID이며, 본 모듈에서는 연동(이벤트/공개 API) 후 채워진다.
 */
public record BookingResponse(
    Long bookingId,
    String listingId,
    BookingStatus status,
    LocalDate moveInDate,
    ContractPeriod contractPeriod,
    Long chatRoomId,
    Instant createdAt) {}
