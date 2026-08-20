package com.kohere.chat.application.dto;

/**
 * 채팅방 목록과 헤더에 공통으로 표시하는 매물 요약이다.
 *
 * <p>매물이 나중에 비공개 또는 삭제돼도 대화 상대와 맥락을 알아볼 수 있도록 채팅방 생성 시 저장한 표시용 사본을 사용한다. 이는 신청 메시지에 고정하는 {@link
 * BookingCardListingResponse}와 목적은 비슷하지만 저장 시점이 다르다. 일반 채팅방 UI는 매물 대표 이미지를 사용하지 않으므로 이미지 URL을 반환하지
 * 않는다. 신청 카드에 표시할 이미지는 {@code bookingCard.listing.thumbnailUrl}로 별도 제공한다.
 */
public record ChatListingSummaryResponse(
    /** 채팅방이 연결된 매물 식별자. */
    String listingId,
    /** 채팅방 생성 시점에 저장한 매물 제목. */
    String title,
    /** 채팅방 생성 시점에 저장한 표시 주소. */
    String address) {}
