package com.kohere.listing.application.dto;

import com.kohere.listing.domain.ConditionTag;
import com.kohere.listing.domain.ListingType;
import java.util.List;

/**
 * 매물 목록 카드 1개를 그리기 위한 요약 응답 DTO다.
 *
 * <p>목록 화면의 카드 1개는 이제 {@code Listing} 하나가 아니라 {@code Listing + roomOffer} 조합을 의미한다. 예를 들어 "A 고시원 /
 * Green Zone 1"과 "A 고시원 / Green Zone 2"는 같은 {@code listingId}를 공유하지만 서로 다른 {@code roomOfferId}를 가진
 * 별도 카드로 내려갈 수 있다.
 *
 * @param listingId 방 상품이 속한 매물 식별자
 * @param roomOfferId 카드가 가리키는 방 상품 식별자
 * @param roomOfferName 카드에 표시할 방 상품명
 * @param title 매물 제목
 * @param type 매물 유형
 * @param monthlyRent 해당 방 상품의 월세
 * @param deposit 해당 방 상품의 보증금
 * @param maintenanceFee 해당 방 상품의 관리비
 * @param availableCount 해당 방 상품 묶음 중 현재 계약 가능한 수량
 * @param thumbnailUrl 목록 카드 이미지 URL
 * @param lat 매물 위도
 * @param lng 매물 경도
 * @param address 카드에 표시할 주소
 * @param conditions 해당 방 상품의 필터 태그
 * @param distanceMeters 요청 bbox의 원본 중심점이 있을 때 계산한 직선 거리
 * @param favorited 현재 사용자 찜 여부
 * @param favoriteCount 매물 전체 찜 수
 */
public record ListingSummaryResponse(
    String listingId,
    String roomOfferId,
    String roomOfferName,
    String title,
    ListingType type,
    int monthlyRent,
    int deposit,
    int maintenanceFee,
    int availableCount,
    String thumbnailUrl,
    double lat,
    double lng,
    String address,
    List<ConditionTag> conditions,
    Integer distanceMeters,
    boolean favorited,
    int favoriteCount) {}
