package com.kohere.listing.api;

import java.util.Set;

/**
 * 매물 추천 조건(모듈 간 전달용 값객체). diagnosis가 저장된 진단 조건으로 구성해 {@link ListingRecommendationService}에 넘긴다.
 *
 * <p>모듈 간 enum은 원시 문자열로 주고받는다(domain-model §1·§2) — {@code region}/{@code conditions}/{@code
 * district}는 diagnosis 소유 enum의 이름(UPPER_SNAKE) 문자열이고, listing이 자기 enum으로 해석한다. 대학은 diagnosis가
 * {@code UniversityGroup}을 개별 대학 코드로 펼친 뒤 전달하므로 listing은 그룹 enum을 알 필요가 없다.
 *
 * <p>③ 대학·지역은 입국 목적 분기에 따라 한쪽 기준만 실질적으로 채워진다. STUDY는 {@code universityCodes}로, NON_STUDY는 {@code
 * district}로 좁힌다. {@code universityCodes}가 비어 있으면 ETC 그룹처럼 대학 필터를 적용하지 않는다.
 *
 * <p>스펙: docs/api/specs/02-diagnosis-recommendation.md §7(RecommendationCriteria).
 *
 * @param region 진단 ① 지역(단일, 예: {@code "SEOUL"})
 * @param monthlyRentMin 진단 ⑤ 월세 하한(KRW 정수, {@code null}=하한 없음)
 * @param monthlyRentMax 진단 ⑤ 월세 상한(KRW 정수, {@code null}=상한 없음)
 * @param conditions 진단 ④ 주거 조건과 파생 매물 필터 이름 집합(0~다수, 예: {@code "PRIVATE_BATH"}, {@code "NO_ARC"})
 * @param universityCodes 진단 ③ 대학 그룹에서 펼친 개별 대학 코드 집합(예: {@code "SNU"}, {@code "CAU"})
 * @param district 진단 ③ 지역(구)(NON_STUDY일 때, 그 외 {@code null}, 예: {@code "GURO_GU"})
 * @param page 0-base 페이지 번호(오프셋 페이지네이션)
 * @param size 페이지 크기(최대 100)
 * @param sort 정렬 키({@code field,(asc|desc)}; 허용: {@code recommended}/{@code price}/{@code
 *     distance})
 */
public record RecommendationCriteria(
    String region,
    Integer monthlyRentMin,
    Integer monthlyRentMax,
    Set<String> conditions,
    Set<String> universityCodes,
    String district,
    int page,
    int size,
    String sort) {

  public RecommendationCriteria {
    conditions = conditions == null ? Set.of() : Set.copyOf(conditions);
    universityCodes = universityCodes == null ? Set.of() : Set.copyOf(universityCodes);
  }
}
