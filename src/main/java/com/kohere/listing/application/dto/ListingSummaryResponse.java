package com.kohere.listing.application.dto;

import com.kohere.listing.domain.ConditionTag;
import com.kohere.listing.domain.ListingType;
import java.util.List;

/**
 * 매물 목록 카드 1개를 그리기 위한 요약 응답 DTO다.
 *
 * <p>목록과 키워드 검색의 카드 1개는 건물/숙소 매물({@code Listing}) 1개를 의미한다. 같은 매물 안에 필터 조건을 만족하는 방 상품({@code
 * roomOffer})이 여러 개 있어도 {@code listingId}는 한 번만 내려가며, 방 상품별로 달라질 수 있는 가격·보증금·관리비·계약기간은 조건을 통과한 방
 * 상품들의 최저~최고 범위로 내려간다.
 *
 * @param listingId 카드가 가리키는 매물 식별자
 * @param title 매물 제목
 * @param type 매물 유형
 * @param minMonthlyRent 조건을 통과한 방 상품 중 최저 월세
 * @param maxMonthlyRent 조건을 통과한 방 상품 중 최고 월세
 * @param minDeposit 조건을 통과한 방 상품 중 최저 보증금
 * @param maxDeposit 조건을 통과한 방 상품 중 최고 보증금
 * @param minMaintenanceFee 조건을 통과한 방 상품 중 최저 관리비
 * @param maxMaintenanceFee 조건을 통과한 방 상품 중 최고 관리비
 * @param availableCount 조건을 통과한 방 상품들의 현재 계약 가능 수량 합계
 * @param minStayMonths 조건을 통과한 방 상품 중 가장 짧은 최소 계약 개월 수
 * @param maxStayMonths 조건을 통과한 방 상품 중 가장 긴 최대 계약 개월 수
 * @param thumbnailUrl 목록 카드 이미지 URL
 * @param lat 매물 위도
 * @param lng 매물 경도
 * @param address 카드에 표시할 주소
 * @param conditions 조건을 통과한 방 상품들이 가진 필터 태그의 합집합
 * @param distanceMeters 요청 bbox의 원본 중심점이 있을 때 계산한 직선 거리
 * @param favorited 현재 사용자 찜 여부
 * @param favoriteCount 매물 전체 찜 수
 */
public record ListingSummaryResponse(
    String listingId,
    String title,
    ListingType type,
    int minMonthlyRent,
    int maxMonthlyRent,
    int minDeposit,
    int maxDeposit,
    int minMaintenanceFee,
    int maxMaintenanceFee,
    int availableCount,
    int minStayMonths,
    int maxStayMonths,
    String thumbnailUrl,
    double lat,
    double lng,
    String address,
    List<ConditionTag> conditions,
    Integer distanceMeters,
    boolean favorited,
    int favoriteCount) {}
