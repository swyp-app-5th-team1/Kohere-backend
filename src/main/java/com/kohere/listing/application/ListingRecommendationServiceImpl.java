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

  /** 진단 조건을 매물 저장소 조회 조건으로 변환하고 추천 응답 view로 매핑한다. */
  @Override
  public PageResponse<RecommendedListingView> recommendByCriteria(RecommendationCriteria criteria) {
    PageResponse<Listing> listings =
        listingRepository.recommend(
            criteria.region(),
            criteria.monthlyBudgetMax(),
            parseConditionTags(criteria.conditions()),
            criteria.university(),
            criteria.district(),
            criteria.page(),
            criteria.size(),
            criteria.sort());
    return PageResponse.of(
        listings.content().stream().map(ListingResponseMapper::toRecommendedView).toList(),
        listings.page());
  }

  /** 문자열 조건 태그를 listing 도메인의 ConditionTag enum으로 변환한다. */
  private static Set<ConditionTag> parseConditionTags(Set<String> conditions) {
    if (conditions == null || conditions.isEmpty()) {
      return Collections.emptySet();
    }
    return conditions.stream().map(ConditionTag::valueOf).collect(Collectors.toUnmodifiableSet());
  }
}
