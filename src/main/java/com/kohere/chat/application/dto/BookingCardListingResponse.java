package com.kohere.chat.application.dto;

/** 신청 카드에 저장하는 신청 시점의 매물 표시 정보. */
public record BookingCardListingResponse(
    String listingId, String thumbnailUrl, String title, String address, int monthlyRent) {}
