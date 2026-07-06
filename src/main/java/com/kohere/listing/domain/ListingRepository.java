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

  /**
   * 지도 범위와 필터 조건을 적용해 목록 카드 후보를 조회한다.
   *
   * <p>목록 화면은 매물/건물 1개를 카드 1개로 보여준다. 다만 가격·보증금·관리비·계약기간·재고는 방 상품별로 달라질 수 있으므로, 반환값은 {@link
   * Listing}과 검색 조건을 통과한 방 상품 목록을 함께 담은 {@link ListingSearchResult}를 페이지 단위로 반환한다.
   */
  PageResponse<ListingSearchResult> search(ListingSearchCondition condition);

  /** 지도 SDK에 전달할 마커 후보를 조회한다. 전체 건수와 지정 상한 내 매물만 반환한다. */
  ListingMapSearchResult searchForMap(ListingSearchCondition condition, int limit);

  /**
   * 진단 결과 조건을 MongoDB 필터로 적용해 추천 매물 페이지를 조회한다.
   *
   * <p>대학은 diagnosis의 그룹 코드가 아니라 그룹에서 펼친 개별 대학 코드 집합으로 받는다. 빈 집합이면 대학 조건을 생략하고, 값이 있으면 Listing 루트의
   * {@code nearbyUniversityCodes}와 ANY 매칭한다. 월세 하한/상한은 같은 활성 roomOffer가 조건 태그와 함께 모두 만족해야 하므로 저장소
   * 구현에서 {@code roomOffers} 배열의 단일 원소 기준으로 검사한다.
   */
  PageResponse<Listing> recommend(
      String region,
      Integer monthlyRentMin,
      Integer monthlyRentMax,
      Set<ConditionTag> conditions,
      Set<String> universityCodes,
      String district,
      int page,
      int size,
      String sort);

  /** 매물 도메인 객체를 저장하고 저장된 결과를 반환한다. */
  Listing save(Listing listing);

  /** 매물 찜 수를 원자적으로 1 증가시키고 변경 후 값을 반환한다. */
  int increaseFavoriteCount(String listingId);

  /** 매물 찜 수를 원자적으로 1 감소시키되 0 미만으로 내리지 않고 변경 후 값을 반환한다. */
  int decreaseFavoriteCount(String listingId);
}
