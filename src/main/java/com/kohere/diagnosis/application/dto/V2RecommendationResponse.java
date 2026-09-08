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
   * 추천 매물 요약(listing 공개 뷰에서 매핑). {@code type}/{@code conditions}는 code/label이다. 월세는 매물의 활성 방 상품 범위를
   * {@code monthlyRentMin}/{@code monthlyRentMax} 두 필드로 노출하고, 보증금도 같은 방식으로 {@code
   * minDeposit}/{@code maxDeposit} 범위를 노출한다. {@code conditions}는 추천 카드 조건 배지에 사용할 매물 단위 조건 목록이다.
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
      List<CodeLabel> conditions) {}

  /** 프론트는 label을 표시하고 code를 필터 요청과 내부 비교에 사용한다. */
  public record CodeLabel(String code, String label) {}

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
