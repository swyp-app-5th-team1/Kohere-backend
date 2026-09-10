package com.kohere.diagnosis.application.dto;

import com.kohere.common.response.PageInfo;
import com.kohere.common.response.PageResponse;
import com.kohere.listing.api.RecommendedListingView;
import java.util.List;

/**
 * 진단 결과 추천 응답({@code GET /api/v2/diagnoses/{id}/recommendations}). 추천 매물 + 지도 좌표 + 페이지 메타를 담는다.
 *
 * <p><b>조정 제안 문구·액션을 주지 않는다</b>(issue #157·ADR-0036) — 매칭 사유만 {@link RecommendationResultCode}로
 * 싣는다. 제안을 쓰지 않는 것과 사유를 주지 않는 것은 다르다.
 *
 * <p><b>매칭 0건은 여기서 드러난다</b> — 흐름 응답({@code FlowResultCode})에 {@code NO_MATCH}가 없는 이유는 그쪽이 추천을 조회하기
 * 전이라 모르기 때문이고, 여기는 조회를 마친 뒤라 안다.
 *
 * @param resultCode 매칭 결과(항상 존재 — {@code MATCHED} 또는 {@code NO_MATCH})
 * @param content 추천 매물 요약 목록(현재 페이지, 0건이면 빈 목록)
 * @param markers 현재 페이지 매물의 지도 좌표
 * @param page 오프셋 페이지 메타
 */
public record V2RecommendationResponse(
    RecommendationResultCode resultCode,
    List<RecommendedListing> content,
    List<RecommendationMapMarker> markers,
    PageInfo page) {

  /**
   * 추천 매물 요약(listing 공개 뷰에서 매핑). {@code type}·{@code conditions}·{@code nearestTransit.type}은
   * code/label이다.
   *
   * <p>월세와 보증금은 범위를 두 필드로 노출하며, 그 범위와 {@code conditions}는 모두 <b>진단 조건을 통과한 방 상품만</b>을 기준으로 집계한 값이다
   * — 조건에 맞지 않는 방의 가격·태그는 카드에 실리지 않는다.
   */
  public record RecommendedListing(
      String listingId,
      String title,
      CodeLabel type,
      int monthlyRentMin,
      int monthlyRentMax,
      int minDeposit,
      int maxDeposit,
      String thumbnailUrl,
      double lat,
      double lng,
      NearestTransit nearestTransit,
      List<CodeLabel> conditions) {}

  /**
   * 카드에 표시할 가까운 교통수단.
   *
   * <p>{@code name}은 카드용 축약 표기다 — 표시 언어가 영어인 지하철역만 {@code Sinchon Sta.} 형태로 줄어들며, 정식 명칭은 매물 상세 조회가
   * 준다.
   */
  public record NearestTransit(CodeLabel type, String name, int walkMinutes) {}

  /** 프론트는 label을 표시하고 code를 필터 요청과 내부 비교에 사용한다. */
  public record CodeLabel(String code, String label) {}

  /** listing 공개 뷰의 교통수단을 응답 타입으로 옮긴다. */
  private static NearestTransit toNearestTransit(RecommendedListingView.NearestTransitView view) {
    return new NearestTransit(
        new CodeLabel(view.type().code(), view.type().label()), view.name(), view.walkMinutes());
  }

  /** listing 공개 추천 결과를 응답으로 매핑한다(사유는 코드로·제안은 없음). */
  public static V2RecommendationResponse from(PageResponse<RecommendedListingView> result) {
    List<RecommendedListing> content =
        result.content().stream()
            .map(
                v ->
                    new RecommendedListing(
                        v.listingId(),
                        v.title(),
                        new CodeLabel(v.type().code(), v.type().label()),
                        v.monthlyRentMin(),
                        v.monthlyRentMax(),
                        v.minDeposit(),
                        v.maxDeposit(),
                        v.thumbnailUrl(),
                        v.lat(),
                        v.lng(),
                        toNearestTransit(v.nearestTransit()),
                        v.conditions().stream()
                            .map(value -> new CodeLabel(value.code(), value.label()))
                            .toList()))
            .toList();
    List<RecommendationMapMarker> markers =
        result.content().stream()
            .map(v -> new RecommendationMapMarker(v.listingId(), v.lat(), v.lng()))
            .toList();
    RecommendationResultCode resultCode =
        content.isEmpty() ? RecommendationResultCode.NO_MATCH : RecommendationResultCode.MATCHED;
    return new V2RecommendationResponse(resultCode, content, markers, result.page());
  }
}
