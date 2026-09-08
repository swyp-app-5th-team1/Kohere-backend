package com.kohere.diagnosis.application.dto;

import com.kohere.listing.api.RecommendedListingMarkersView;
import java.util.List;

/**
 * 진단 추천 전체 지도 마커 응답({@code GET /api/v2/diagnoses/{id}/recommendations/map}). 페이지 조회와 달리 좌표만 담는다 —
 * 매물 카드 정보는 상세에서 가져간다.
 *
 * <p>마커 타입은 페이지 조회({@link V2RecommendationResponse})와 <b>같은</b> {@link RecommendationMapMarker}다. 두
 * 응답의 마커 모양이 갈리면 같은 지도 화면이 어느 쪽을 받았는지에 따라 다르게 그려진다.
 *
 * <p><b>매칭 0건은 에러가 아니다</b> — 빈 배열과 {@code total=0}이 곧 답이다. 페이지가 없어 "마지막 페이지를 넘겨 요청해 비었다"는 모호성 자체가
 * 없으므로 결과코드를 따로 싣지 않는다.
 *
 * @param markers 추천 조건에 맞는 매물의 지도 좌표(서버 상한까지)
 * @param total 조건에 맞는 전체 매물 수(절단 전). {@code markers}보다 크면 상한에 걸려 일부만 실린 것이다
 */
public record V2RecommendationMapResponse(List<RecommendationMapMarker> markers, long total) {

  /** listing 공개 마커 결과를 응답으로 매핑한다(필드명·타입·순서가 같아 그대로 옮긴다). */
  public static V2RecommendationMapResponse from(RecommendedListingMarkersView view) {
    return new V2RecommendationMapResponse(
        view.markers().stream()
            .map(m -> new RecommendationMapMarker(m.listingId(), m.lat(), m.lng()))
            .toList(),
        view.total());
  }
}
