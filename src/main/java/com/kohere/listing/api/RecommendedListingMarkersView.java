package com.kohere.listing.api;

import java.util.List;

/**
 * 진단 추천 매물의 지도 마커 목록(모듈 간 전달용 published view). 지도 화면은 좌표만 필요하고 카드 정보는 상세에서 가져가므로, 페이지 조회용 {@link
 * RecommendedListingView}와 달리 제목·가격·이미지·조건을 싣지 않는다 — 번역할 라벨이 없어 표시 언어도 받지 않는다.
 *
 * <p><b>{@code total}은 절단 전 전체 매칭 수</b>다. {@code markers}는 서버 상한까지만 담기므로 {@code markers.size() <
 * total}이면 일부만 실린 것이다. 상한을 넘겨도 오류로 만들지 않는 이유는 진단이 조건 고정이라 호출자가 범위를 좁힐 수단이 없기 때문이다(bbox로 좁힐 수 있는 매물
 * 지도 조회와 다른 점이다).
 *
 * @param markers 조건에 맞는 매물의 지도 좌표(상한까지)
 * @param total 조건에 맞는 전체 매물 수(절단 전)
 */
public record RecommendedListingMarkersView(List<Marker> markers, long total) {

  public RecommendedListingMarkersView {
    markers = List.copyOf(markers);
  }

  /**
   * 지도에 찍을 매물 하나의 식별자와 좌표.
   *
   * <p>축 순서에 주의한다 — 저장 모델의 좌표는 경도가 먼저이고 이 record는 위도가 먼저다. 둘 다 {@code double}이라 뒤바꿔도 컴파일된다.
   */
  public record Marker(String listingId, double lat, double lng) {}
}
