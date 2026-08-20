package com.kohere.chat.application.dto;

/**
 * 신청 카드에 고정하는 신청 시점의 매물 표시 정보다.
 *
 * <p>현재 매물을 매번 다시 조회해 카드를 조립하면 제목이나 가격 변경으로 과거 신청의 의미가 달라질 수 있다. 따라서 서버가 신청 카드 생성 시점의 표시값을 복사해 메시지
 * payload로 저장한다.
 */
public record BookingCardListingResponse(
    /** 신청 대상 매물 식별자. */
    String listingId,
    /** 신청 시점의 대표 이미지 URL. */
    String thumbnailUrl,
    /** 신청 시점의 매물 제목. */
    String title,
    /** 신청 시점의 표시 주소. */
    String address,
    /** 신청 시점의 월세 금액(KRW). */
    int monthlyRent) {}
