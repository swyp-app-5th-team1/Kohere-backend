package com.kohere.listing.domain;

import com.kohere.common.response.PageResponse;
import java.util.Optional;
import java.util.Set;

/**
 * 매물 영속 포트. 구현은 infrastructure 계층에 두어 의존성을 역전한다(docs/convention/code-style.md §3-3). 도메인은 영속 기술을
 * 모른다.
 *
 * <p>목록 조회는 {@link ListingSearchCondition}으로 지도 범위·가격·옵션 조건을 한 번에 전달한다.
 */
public interface ListingRepository {

  /** ObjectId 문자열로 매물 한 건을 조회한다. */
  Optional<Listing> findById(String listingId);

  /** 공개 중이고 활성 방 상품이 있는 매물만 페이지로 조회한다. */
  PageResponse<Listing> findPublished(int page, int size);

  /** 지도 범위와 필터 조건을 적용해 공개 매물 목록을 조회한다. */
  PageResponse<Listing> search(ListingSearchCondition condition);

  /** 진단 결과 조건을 MongoDB 필터로 적용해 추천 매물 페이지를 조회한다. */
  PageResponse<Listing> recommend(
      String region,
      int monthlyBudgetMax,
      Set<ConditionTag> conditions,
      String university,
      String district,
      int page,
      int size,
      String sort);

  /** 매물 도메인 객체를 저장하고 저장된 결과를 반환한다. */
  Listing save(Listing listing);
}
