package com.kohere.listing.application;

import com.kohere.common.response.PageResponse;
import com.kohere.listing.api.ListingRecommendationService;
import com.kohere.listing.api.RecommendationCriteria;
import com.kohere.listing.api.RecommendedListingView;
import com.kohere.listing.domain.ConditionTag;
import com.kohere.listing.domain.Listing;
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

  private final ListingRepository listingRepository;
  private final ListingLocalizationService listingLocalizationService;

  /**
   * 진단 조건을 매물 저장소 조회 조건으로 변환하고 추천 응답 view로 매핑한다.
   *
   * <p>diagnosis는 모듈 경계를 넘기 위해 조건 태그와 대학 코드를 문자열로 전달한다. 이 서비스는 listing 도메인이 이해하는 {@link
   * ConditionTag}로 조건 태그만 변환하고, 대학 코드는 listing 저장 모델의 {@code nearbyUniversityCodes} 값과 같은 원시 코드라
   * 그대로 저장소에 넘긴다.
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
            criteria.region(),
            criteria.monthlyRentMin(),
            criteria.monthlyRentMax(),
            parseConditionTags(criteria.conditions()),
            criteria.universityCodes(),
            criteria.district(),
            criteria.page(),
            criteria.size(),
            criteria.sort());
    return PageResponse.of(
        listings.content().stream()
            .map(listing -> ListingResponseMapper.toRecommendedView(listing, localization))
            .toList(),
        listings.page());
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
