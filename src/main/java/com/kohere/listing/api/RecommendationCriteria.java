package com.kohere.listing.api;

import java.util.Set;

/**
 * 매물 추천 조건(모듈 간 전달용 값객체). diagnosis가 저장된 진단 조건으로 구성해 {@link ListingRecommendationService}에 넘긴다.
 *
 * <p>모듈 간 enum은 원시 문자열로 주고받는다(domain-model §1·§2) — {@code region}/{@code conditions}/{@code
 * university}/{@code district}는 diagnosis 소유 enum의 이름(UPPER_SNAKE) 문자열이고, listing이 자기 enum으로 해석한다.
 * ③ 대학·지역은 입국 목적 분기에 따라 한쪽만 채워진다(STUDY→{@code university}, NON_STUDY→{@code district}; 나머지는 {@code
 * null}).
 *
 * <p>스펙: docs/api/specs/02-diagnosis-recommendation.md §7(RecommendationCriteria).
 *
 * @param region 진단 ① 지역(단일, 예: {@code "SEOUL"})
 * @param monthlyBudgetMax 진단 ⑤ 월 예산 상한(KRW 정수)
 * @param conditions 진단 ④ 주거 조건 이름 집합(0~다수, 예: {@code "PRIVATE_BATH"})
 * @param university 진단 ③ 대학(STUDY일 때, 그 외 {@code null}, 예: {@code "SNU"})
 * @param district 진단 ③ 지역(구)(NON_STUDY일 때, 그 외 {@code null}, 예: {@code "GURO_GU"})
 * @param page 0-base 페이지 번호(오프셋 페이지네이션)
 * @param size 페이지 크기(최대 100)
 * @param sort 정렬 키({@code field,(asc|desc)}; 허용: {@code recommended}/{@code price}/{@code
 *     distance})
 */
public record RecommendationCriteria(
    String region,
    int monthlyBudgetMax,
    Set<String> conditions,
    String university,
    String district,
    int page,
    int size,
    String sort) {}
