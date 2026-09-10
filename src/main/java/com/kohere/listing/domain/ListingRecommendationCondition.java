package com.kohere.listing.domain;

import java.util.Set;

/**
 * 진단 추천의 매칭 조건. 페이지 조회와 마커 조회가 <b>같은 타입</b>을 받게 해 "두 경로가 같은 매물 집합을 낸다"를 타입으로 보장한다.
 *
 * <p>조건을 위치 인자로 늘어놓으면 {@code region}·{@code district}·{@code arcStatus}가 모두 {@code String}이고 대학 코드
 * 둘이 모두 {@code Set<String>}이라 <b>순서가 뒤바뀌어도 컴파일된다</b> — 두 호출 지점이 조용히 어긋나는 것을 막으려 값객체로 묶는다.
 *
 * <p>페이지네이션·정렬은 여기 담지 않는다. 마커 조회에는 그런 것이 없고, 조건과 표현은 서로 다른 관심사다.
 *
 * @param region 진단 ① 지역({@code null}이면 지역 조건 없음)
 * @param monthlyRentMin 월세 하한({@code null}=하한 없음)
 * @param monthlyRentMax 월세 상한({@code null}=상한 없음)
 * @param conditions 주거 조건 태그(빈 집합이면 조건 없음)
 * @param includedUniversityCodes 이 중 하나라도 인근이면 매칭(빈 집합이면 대학 조건 없음)
 * @param excludedUniversityCodes 이 중 어느 것도 인근이 아니어야 매칭("그 외 대학")
 * @param district 진단 ③ 지역구(NON_STUDY일 때, 그 외 {@code null})
 * @param arcStatus 진단 ⑥ ARC 발급 여부
 */
public record ListingRecommendationCondition(
    String region,
    Integer monthlyRentMin,
    Integer monthlyRentMax,
    Set<ConditionTag> conditions,
    Set<String> includedUniversityCodes,
    Set<String> excludedUniversityCodes,
    String district,
    String arcStatus) {

  public ListingRecommendationCondition {
    conditions = conditions == null ? Set.of() : Set.copyOf(conditions);
    includedUniversityCodes =
        includedUniversityCodes == null ? Set.of() : Set.copyOf(includedUniversityCodes);
    excludedUniversityCodes =
        excludedUniversityCodes == null ? Set.of() : Set.copyOf(excludedUniversityCodes);
  }

  /** roomOffers.filterTags에 저장되는 조건이다. 진단 조건과 저장 태그가 1:1이라 그대로 반환한다. */
  public Set<ConditionTag> roomOfferConditions() {
    return conditions;
  }

  /**
   * 방 상품 하나가 진단 조건을 만족하는지 판정한다.
   *
   * <p><b>저장소가 만드는 {@code $elemMatch}와 반드시 같은 술어여야 한다.</b> Mongo는 "조건을 만족하는 방이 하나라도 있는 매물"을 고르고, 이
   * 메서드는 그 매물 안에서 실제로 만족한 방을 추린다. 둘이 어긋나면 매칭 방이 0개가 되어 카드 집계가 폴백으로 떨어진다. 그래서 양쪽이 {@link
   * #roomOfferConditions()}와 월세 경계라는 <b>같은 값</b>을 읽는다.
   */
  public boolean matches(Listing.RoomOffer roomOffer) {
    if (roomOffer.status() != Listing.RoomOfferStatus.ACTIVE) {
      return false;
    }
    if (monthlyRentMin != null && roomOffer.pricing().monthlyRent() < monthlyRentMin) {
      return false;
    }
    if (monthlyRentMax != null && roomOffer.pricing().monthlyRent() > monthlyRentMax) {
      return false;
    }
    return roomOffer.filterTags().containsAll(conditions);
  }
}
