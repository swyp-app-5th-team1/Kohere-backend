package com.kohere.listing.application;

import com.kohere.common.response.PageResponse;
import com.kohere.listing.api.ListingRecommendationService;
import com.kohere.listing.api.RecommendationCriteria;
import com.kohere.listing.api.RecommendedListingMarkersView;
import com.kohere.listing.api.RecommendedListingView;
import com.kohere.listing.domain.ConditionTag;
import com.kohere.listing.domain.Listing;
import com.kohere.listing.domain.ListingMapSearchResult;
import com.kohere.listing.domain.ListingRecommendationCondition;
import com.kohere.listing.domain.ListingRepository;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 매물 추천 공개 쿼리 구현. diagnosis가 진단 조건을 {@link RecommendationCriteria}로 묶어 동기 호출한다(ADR-0002 Decision
 * 5).
 *
 * <p>listing 모듈의 MongoDB 컬렉션만 조회하고, diagnosis 엔티티나 컬렉션에는 접근하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class ListingRecommendationServiceImpl implements ListingRecommendationService {

  /**
   * 마커 응답 상한. {@code ListingService.MAX_MAP_MARKERS}와 같은 값이며 근거도 같다 — 지도 SDK가 한 번에 받아 그릴 수 있는 마커
   * 수다. 초과분은 오류가 아니라 절단이다(호출자가 조건을 좁힐 수단이 없다).
   */
  private static final int MAX_RECOMMENDATION_MARKERS = 500;

  private final ListingRepository listingRepository;
  private final ListingLocalizationService listingLocalizationService;

  /**
   * 진단 조건을 매물 저장소 조회 조건으로 변환하고 추천 응답 view로 매핑한다.
   *
   * <p>diagnosis는 모듈 경계를 넘기 위해 조건 태그와 대학 코드를 문자열로 전달한다. 이 서비스는 listing 도메인이 이해하는 {@link
   * ConditionTag}로 조건 태그만 변환하고, 대학 코드는 listing 저장 모델의 {@code nearbyUniversityCodes} 값과 같은 원시 코드라
   * 그대로 저장소에 넘긴다 — 포함(선택 그룹의 멤버)과 제외("그 외 대학") 두 집합 모두 마찬가지다.
   */
  @Override
  public PageResponse<RecommendedListingView> recommendByCriteria(RecommendationCriteria criteria) {
    return recommendByCriteria(criteria, "en");
  }

  /** 추천 카드의 제목·type·conditions를 지정 언어로 조립한다. */
  @Override
  public PageResponse<RecommendedListingView> recommendByCriteria(
      RecommendationCriteria criteria, String language) {
    ListingLocalizationContext localization = listingLocalizationService.contextFor(language);
    PageResponse<Listing> listings =
        listingRepository.recommend(
            toCondition(criteria), criteria.page(), criteria.size(), criteria.sort());
    return PageResponse.of(
        listings.content().stream()
            .map(listing -> ListingResponseMapper.toRecommendedView(listing, localization))
            .toList(),
        listings.page());
  }

  /**
   * 같은 조건으로 지도 마커만 조회한다. 표시 언어를 받지 않는 이유는 마커에 번역할 라벨이 하나도 없기 때문이다 — 로컬라이제이션 컨텍스트를 만들지 않는다.
   *
   * <p>{@code criteria}의 페이지네이션·정렬 필드는 <b>쓰지 않는다</b>. 이 경로는 페이지를 나누지 않고, 정렬은 절단 경계가 흔들리지 않도록 저장소가
   * 고정한다.
   */
  @Override
  public RecommendedListingMarkersView recommendMarkersByCriteria(RecommendationCriteria criteria) {
    ListingMapSearchResult result =
        listingRepository.recommendForMap(toCondition(criteria), MAX_RECOMMENDATION_MARKERS);
    return new RecommendedListingMarkersView(
        result.listings().stream()
            // 축 순서는 ListingResponseMapper.toMapMarker와 같다 — 위도가 먼저다.
            .map(
                listing ->
                    new RecommendedListingMarkersView.Marker(
                        listing.getId(),
                        listing.getLocation().latitude(),
                        listing.getLocation().longitude()))
            .toList(),
        result.total());
  }

  /** 모듈 경계를 문자열로 넘어온 조건을 listing 도메인 조건 객체로 복원한다. */
  private static ListingRecommendationCondition toCondition(RecommendationCriteria criteria) {
    return new ListingRecommendationCondition(
        criteria.region(),
        criteria.monthlyRentMin(),
        criteria.monthlyRentMax(),
        parseConditionTags(criteria.conditions()),
        criteria.includedUniversityCodes(),
        criteria.excludedUniversityCodes(),
        criteria.district(),
        criteria.arcStatus());
  }

  /**
   * 문자열 조건 태그를 listing 도메인의 ConditionTag enum으로 변환한다.
   *
   * <p>diagnosis의 {@code DiagnosisCondition}과 listing의 {@link ConditionTag} 이름은 최신 UI 필터 코드 기준으로
   * 1:1 통일되어 있다. 이 메서드는 모듈 경계를 문자열로 넘긴 조건을 listing 도메인 enum으로 복원한다.
   */
  private static Set<ConditionTag> parseConditionTags(Set<String> conditions) {
    if (conditions == null || conditions.isEmpty()) {
      return Collections.emptySet();
    }
    return conditions.stream().map(ConditionTag::valueOf).collect(Collectors.toUnmodifiableSet());
  }
}
