package com.kohere.listing.api;

import java.util.List;

/**
 * 추천 매물 요약(모듈 간 전달용 published view). 모듈 경계를 넘으므로 내부 enum·다국어 타입을 공유하지 않고 {@code type}·{@code
 * conditions}·{@code nearestTransit}의 종류를 code/label published view로 노출한다(domain-model §1·§2). 사용자별
 * 찜 상태(favorited 등)는 추천 컨텍스트에 불필요하여 제외한다.
 *
 * <p>가격 범위와 조건 배지는 <b>진단 조건을 통과한 방 상품만</b>을 기준으로 집계한 값이다. 조건에 맞는 방이 있어 매칭된 매물이어도 조건에 맞지 않는 방의
 * 가격·태그는 여기 실리지 않는다.
 *
 * <p>지도 마커(listingId/lat/lng)는 호출자(diagnosis)가 이 view에서 파생한다. 정본 매물 요약 스키마는 매물 탐색(03) 스펙이다.
 *
 * @param listingId 매물 식별자
 * @param title 매물 제목
 * @param type 매물 유형의 안정적인 code와 사용자 언어 label
 * @param monthlyRentMin 월세 범위 하한(KRW 정수) — 진단 조건을 통과한 방 상품 중 최저 월세
 * @param monthlyRentMax 월세 범위 상한(KRW 정수) — 진단 조건을 통과한 방 상품 중 최고 월세
 * @param minDeposit 보증금 범위 하한(KRW 정수) — 진단 조건을 통과한 방 상품 중 최저 보증금
 * @param maxDeposit 보증금 범위 상한(KRW 정수) — 진단 조건을 통과한 방 상품 중 최고 보증금
 * @param thumbnailUrl 대표 이미지 URL
 * @param lat 위도(지도 마커)
 * @param lng 경도(지도 마커)
 * @param nearestTransit 카드에 표시할 가까운 교통수단
 * @param conditions 추천 카드 조건 배지에 사용할 code/label 목록. 진단 조건을 통과한 방 상품의 {@code filterTags} 합집합이다. 프론트는
 *     label을 표시하고 code를 필터 요청에 사용한다.
 */
public record RecommendedListingView(
    String listingId,
    String title,
    ListingCodeLabelView type,
    int monthlyRentMin,
    int monthlyRentMax,
    int minDeposit,
    int maxDeposit,
    String thumbnailUrl,
    double lat,
    double lng,
    NearestTransitView nearestTransit,
    List<ListingCodeLabelView> conditions) {

  /**
   * 카드에 표시할 가까운 교통수단이다. 모듈 경계를 넘으므로 종류는 code/label로, 역명은 이미 표시 언어가 적용된 문자열로 평탄화한다.
   *
   * <p>역명은 <b>카드용 축약 표기</b>다 — 표시 언어가 영어이고 지하철역 이름이 {@code " Station"}으로 끝날 때만 {@code " Sta."}로 줄여
   * 내려간다. 매물 목록 카드와 같은 규칙이라 같은 매물이 두 화면에서 같은 문자열로 보인다.
   *
   * @param type 교통수단 종류의 안정적인 code와 사용자 언어 label
   * @param name 역·정류장 이름(사용자 언어, 카드용 축약 표기)
   * @param walkMinutes 도보 소요 시간(분)
   */
  public record NearestTransitView(ListingCodeLabelView type, String name, int walkMinutes) {}
}
