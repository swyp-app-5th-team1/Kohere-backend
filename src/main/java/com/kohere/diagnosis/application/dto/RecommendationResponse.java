package com.kohere.diagnosis.application.dto;

import com.kohere.common.response.PageInfo;
import java.util.List;

/**
 * 진단 결과(추천 매물) 응답 DTO. 추천 매물 목록·지도 마커·페이지 메타를 한 객체로 담는다. 매칭이 0건이면 {@code content}/{@code markers}는
 * 빈 목록이고 {@code suggestions}(조정 제안)를 채운다(에러 아님).
 *
 * <p>매물 데이터는 listing 모듈 공개 쿼리({@code recommendByCriteria})로 받아 모듈 경계를 넘어오므로 enum은 원시 문자열로 노출한다. 지도
 * 마커는 매물 좌표에서 파생한다.
 *
 * <p>docs/api/specs/02-diagnosis-recommendation.md §7 · 시퀀스 US-2-2.
 *
 * @param content 추천 매물 요약 목록(현재 페이지)
 * @param markers 현재 페이지 매물의 지도 좌표
 * @param page 오프셋 페이지 메타
 * @param suggestions 매칭 0건일 때 조건/예산 조정 제안(결과가 있으면 null)
 */
public record RecommendationResponse(
    List<RecommendedListing> content,
    List<MapMarker> markers,
    PageInfo page,
    Suggestions suggestions) {

  /**
   * 추천 매물 요약(listing 공개 뷰에서 매핑). type·conditions는 원시 문자열. 월세는 매물의 활성 방 상품 범위를 {@code
   * monthlyRentMin}/{@code monthlyRentMax} 두 필드로 노출하고, 보증금도 같은 방식으로 {@code minDeposit}/{@code
   * maxDeposit} 범위를 노출한다.
   */
  public record RecommendedListing(
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

  /** 지도 마커 좌표. */
  public record MapMarker(String listingId, double lat, double lng) {}

  /** 매칭 0건일 때의 조정 제안. {@code reason}/{@code actions[].type}은 언어 무관 enum, message/detail은 번역본. */
  public record Suggestions(String reason, String message, List<SuggestionAction> actions) {}

  /** 조정 제안 액션(RELAX_REGION/RELAX_CONDITIONS/INCREASE_BUDGET). */
  public record SuggestionAction(String type, String detail) {}
}
