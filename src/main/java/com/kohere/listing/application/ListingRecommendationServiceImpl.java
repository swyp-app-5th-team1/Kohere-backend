package com.kohere.listing.application;

import com.kohere.common.response.PageInfo;
import com.kohere.common.response.PageResponse;
import com.kohere.listing.api.ListingRecommendationService;
import com.kohere.listing.api.RecommendationCriteria;
import com.kohere.listing.api.RecommendedListingView;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 매물 추천 공개 쿼리 구현. diagnosis가 진단 조건을 {@link RecommendationCriteria}로 묶어 동기 호출한다(ADR-0002 Decision
 * 5).
 *
 * <p>매칭·정렬·페이징을 수행할 listing 영속 계층이 아직 없으므로 현재는 빈 결과(매칭 0건)를 반환한다 — 이는 스펙상 유효한 흐름이며 호출 측(diagnosis)이
 * 0건 분기로 조정 제안(suggestions)을 구성한다. TODO: listing 영속(매물 컬렉션) 도입 시 criteria 기반 실제 매칭으로 교체한다.
 */
@Service
public class ListingRecommendationServiceImpl implements ListingRecommendationService {

  @Override
  public PageResponse<RecommendedListingView> recommendByCriteria(RecommendationCriteria criteria) {
    List<RecommendedListingView> content = List.of();
    PageInfo page = new PageInfo(criteria.page(), criteria.size(), 0L, 0, false);
    return PageResponse.of(content, page);
  }
}
