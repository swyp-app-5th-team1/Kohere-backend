package com.kohere.diagnosis.application.dto;

/**
 * 진단 추천 결과의 지도 마커 좌표. 페이지 조회({@link V2RecommendationResponse})와 마커 전용 조회가 같은 타입을 쓴다 — 두 응답의 마커 모양이
 * 갈리면 지도 화면이 어느 쪽을 받았는지에 따라 다르게 그려진다.
 *
 * <p>축 순서에 주의한다 — 도메인 좌표({@code Listing.GeoPoint})는 경도가 먼저이고 이 record는 위도가 먼저다. 둘 다 {@code
 * double}이라 뒤바꿔도 컴파일된다.
 */
public record RecommendationMapMarker(String listingId, double lat, double lng) {}
