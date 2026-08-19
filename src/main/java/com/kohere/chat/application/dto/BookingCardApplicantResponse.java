package com.kohere.chat.application.dto;

/** 신청 카드에 저장하는 신청자 표시 정보. */
public record BookingCardApplicantResponse(
    Long userId, String name, String gender, String country, String countryName, String email) {}
