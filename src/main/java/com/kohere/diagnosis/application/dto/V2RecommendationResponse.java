package com.kohere.diagnosis.application.dto;

import com.kohere.common.response.PageInfo;
import com.kohere.common.response.PageResponse;
import com.kohere.listing.api.RecommendedListingView;
import java.util.List;

/**
 * v2 진단 결과 추천 응답({@code GET /api/v2/diagnoses/{id}/recommendations}). 추천 매물 + 지도 좌표 + 페이지 메타를 담으며,
 * v1 {@link RecommendationResponse}의 중첩 {@code RecommendedListing}/{@code MapMarker}를 그대로 재사용한다.
 *
 * <p>v1과 다른 점은 <b>조정 제안 문구·액션이 없다</b>는 것 하나다(issue #157·ADR-0036). v1은 0건일 때 {@code suggestions
 * {reason:"NO_MATCH", message, actions[]}}를 채워 <b>사유와 제안을 한 덩어리로</b> 주는데, v2는 "지역을 넓혀 보세요" 같은 제안을
 * 쓰지 않기로 했으므로(요구3) 그 덩어리에서 <b>사유만</b> 떼어 {@link RecommendationResultCode}로 둔다 — 제안을 안 쓰는 것과 사유를 안
 * 주는 것은 다르다. v1은 제안 기능을 계속 쓰므로 그 응답은 그대로 둔다.
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
    List<RecommendationResponse.RecommendedListing> content,
    List<RecommendationResponse.MapMarker> markers,
    PageInfo page) {

  /** listing 공개 추천 결과를 v2 응답으로 매핑한다(v1과 동일 필드 복사, 사유는 코드로·제안은 없음). */
  public static V2RecommendationResponse from(PageResponse<RecommendedListingView> result) {
    List<RecommendationResponse.RecommendedListing> content =
        result.content().stream()
            .map(
                v ->
                    new RecommendationResponse.RecommendedListing(
                        v.listingId(),
                        v.title(),
                        new RecommendationResponse.CodeLabel(v.type().code(), v.type().label()),
                        v.monthlyRentMin(),
                        v.monthlyRentMax(),
                        v.minDeposit(),
                        v.maxDeposit(),
                        v.thumbnailUrl(),
                        v.lat(),
                        v.lng(),
                        v.conditions().stream()
                            .map(
                                value ->
                                    new RecommendationResponse.CodeLabel(
                                        value.code(), value.label()))
                            .toList()))
            .toList();
    List<RecommendationResponse.MapMarker> markers =
        result.content().stream()
            .map(v -> new RecommendationResponse.MapMarker(v.listingId(), v.lat(), v.lng()))
            .toList();
    RecommendationResultCode resultCode =
        content.isEmpty() ? RecommendationResultCode.NO_MATCH : RecommendationResultCode.MATCHED;
    return new V2RecommendationResponse(resultCode, content, markers, result.page());
  }
}
