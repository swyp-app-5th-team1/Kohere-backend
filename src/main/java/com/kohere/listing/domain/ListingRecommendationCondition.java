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
}
