package com.kohere.chat.application.dto;

import java.time.LocalDate;

/** 신청 완료 후 서버가 만드는 채팅 카드. 기존 Booking 상세 데이터 조립 로직을 재사용하고 신청 시점 값을 메시지 payload로 저장한다. */
public record BookingCardResponse(
    Long bookingId,
    BookingCardListingResponse listing,
    BookingCardApplicantResponse applicant,
    String roomOfferId,
    String roomOfferName,
    LocalDate moveInDate,
    int contractPeriod,
    int deposit,
    int totalAmount) {}
