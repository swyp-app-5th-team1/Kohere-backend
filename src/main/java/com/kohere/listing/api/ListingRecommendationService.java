package com.kohere.listing.api;

import com.kohere.common.response.PageResponse;

/**
 * 매물 추천 공개 쿼리. diagnosis가 진단 조건을 {@link RecommendationCriteria} 값객체로 묶어 동기 호출하면(ADR-0002 Decision
 * 5), listing이 자기 MongoDB 컬렉션만 질의해 매칭 매물 요약 + 좌표를 반환한다(cross-store 조인 없음, ADR-0005 Decision 2).
 *
 * <p>엔티티·내부 enum을 공유하지 않으며 published view({@link RecommendedListingView})로만 결과를 노출한다. 매칭·정렬·페이징 구현은
 * listing 내부(application/infrastructure) 책임이며 본 API 범위 밖이다.
 *
 * <p>스펙: docs/api/specs/02-diagnosis-recommendation.md §7 · 시퀀스 US-2-2.
 */
public interface ListingRecommendationService {

  /**
   * 진단 조건으로 매칭한 추천 매물을 오프셋 페이지로 반환한다(매칭 0건이면 빈 content). 조정 제안(suggestions)·소유권 검증은 호출자(diagnosis)
   * 책임이며, 언어를 별도로 전달하지 않는 내부 존재 확인 호출은 영어를 사용한다.
   *
   * @param criteria 매칭 조건(지역·예산·조건·대학/지역) + 페이지네이션·정렬
   * @return 추천 매물 요약 + 좌표의 오프셋 페이지
   */
  PageResponse<RecommendedListingView> recommendByCriteria(RecommendationCriteria criteria);

  /**
   * 진단 사용자의 표시 언어를 적용해 추천 매물을 반환한다.
   *
   * @param criteria 매칭 조건과 페이지네이션
   * @param language user 모듈이 결정한 ISO 639-1 표시 언어. 미지원 값은 영어로 폴백
   */
  PageResponse<RecommendedListingView> recommendByCriteria(
      RecommendationCriteria criteria, String language);

  /**
   * 같은 조건에 맞는 매물의 <b>지도 마커만</b> 페이지 없이 반환한다(서버 상한까지).
   *
   * <p>지도 화면이 조건에 맞는 매물 전체를 한 번에 찍기 위한 경로다. 페이지 조회는 크기 상한이 있어 그 이상을 받을 수 없고, 카드 정보까지 함께 실려 응답이
   * 불필요하게 커진다. 매칭 조건은 {@link #recommendByCriteria}와 <b>완전히 동일</b>하다 — 같은 진단이면 같은 매물 집합이다.
   *
   * <p>표시 언어를 받지 않는다. 마커에는 번역할 라벨이 하나도 없다.
   *
   * <p>{@code criteria}의 페이지네이션·정렬 필드는 무시한다.
   *
   * @param criteria 매칭 조건(페이지·정렬 필드는 쓰이지 않는다)
   * @return 상한까지의 마커와 절단 전 전체 매칭 수
   */
  RecommendedListingMarkersView recommendMarkersByCriteria(RecommendationCriteria criteria);
}
