package com.kohere.chat.application.dto;

import java.time.LocalDate;

/**
 * 신청 완료 후 서버가 만드는 채팅 카드다.
 *
 * <p>{@code BOOKING_CARD}는 사용자가 STOMP SEND로 만들 수 없다. 서버가 검증된 신청 정보를 기존 Booking 상세 조립 규칙으로 모은 뒤 신청
 * 시점 값을 메시지 payload에 저장한다. 이 구분은 클라이언트가 임의의 신청 카드나 금액을 위조하지 못하게 한다.
 */
public record BookingCardResponse(
    /** 카드의 근거가 되는 신청 식별자. */
    Long bookingId,
    /** 신청 시점에 고정한 매물 표시 정보. */
    BookingCardListingResponse listing,
    /** 신청 시점에 고정한 신청자 표시 정보. */
    BookingCardApplicantResponse applicant,
    /** 신청한 방 상품 식별자. */
    String roomOfferId,
    /** 신청 시점의 방 상품 표시명. */
    String roomOfferName,
    /** 신청자가 선택한 입주 희망일. */
    LocalDate moveInDate,
    /** 계약 기간(개월). 프런트 계약과 동일하게 별도 단위 접미사 없이 제공한다. */
    int contractPeriod,
    /** 신청 시점의 보증금(KRW). */
    int deposit,
    /** 신청 시점 규칙으로 계산한 총액(KRW). */
    int totalAmount) {}
