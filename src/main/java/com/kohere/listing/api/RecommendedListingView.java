package com.kohere.listing.api;

import java.util.List;

/**
 * 추천 매물 요약(모듈 간 전달용 published view). listing 내부 {@code ListingSummaryResponse}에 대응하되, 모듈 경계를 넘으므로
 * 내부 enum을 공유하지 않고 {@code type}/{@code conditions}를 원시 문자열로 노출한다(domain-model §1·§2). 사용자별 찜
 * 상태(favorited 등)는 추천 컨텍스트에 불필요하여 제외한다.
 *
 * <p>지도 마커(listingId/lat/lng)는 호출자(diagnosis)가 이 view에서 파생한다. 정본 매물 요약 스키마는 매물 탐색(03) 스펙이다.
 *
 * @param listingId 매물 식별자
 * @param title 매물 제목
 * @param type 매물 유형 이름(예: {@code "GOSIWON"})
 * @param monthlyRentMin 월세 범위 하한(KRW 정수) — 활성 방 상품 중 최저 월세
 * @param monthlyRentMax 월세 범위 상한(KRW 정수) — 활성 방 상품 중 최고 월세
 * @param minDeposit 보증금 범위 하한(KRW 정수) — 활성 방 상품 중 최저 보증금
 * @param maxDeposit 보증금 범위 상한(KRW 정수) — 활성 방 상품 중 최고 보증금
 * @param thumbnailUrl 대표 이미지 URL
 * @param lat 위도(지도 마커)
 * @param lng 경도(지도 마커)
 * @param conditions 주거 조건 태그 이름 목록(예: {@code "PRIVATE_BATH"})
 */
public record RecommendedListingView(
    String listingId,
    String title,
    String type,
    int monthlyRentMin,
    int monthlyRentMax,
    int minDeposit,
    int maxDeposit,
    String thumbnailUrl,
    double lat,
    double lng,
    List<String> conditions) {}
