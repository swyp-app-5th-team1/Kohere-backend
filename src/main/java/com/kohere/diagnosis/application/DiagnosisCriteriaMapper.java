package com.kohere.diagnosis.application;

import com.kohere.diagnosis.domain.Diagnosis;
import com.kohere.diagnosis.domain.UniversityGroup;
import com.kohere.listing.api.RecommendationCriteria;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 진단 조건을 listing 공개 추천 쿼리의 값객체({@link RecommendationCriteria})로 변환한다. 흐름의 지역 조기 게이트와 확정 뒤 추천 조회가 함께
 * 쓴다.
 *
 * <p>대학 그룹은 diagnosis 개념이므로 이 경계에서 개별 대학 코드로 펼친다({@code HONGIK_YONSEI_EWHA} → {@code HONGIK},
 * {@code YONSEI}, {@code EWHA}). {@code ETC}("그 외 대학")는 펼칠 멤버가 없는 대신 <b>목록 전체를 제외 조건으로</b> 넘긴다 —
 * 목록에 든 대학 근처 매물이 답일 수 없기 때문이다. 어느 쪽이든 listing은 그룹 이름을 알지 못한다. 부분 초안(예: 지역만 채워진 상태)에도 그대로 적용되므로
 * 지역-only 조건은 region만 채우고 나머지는 비운 결과가 된다(지역 조기 게이트).
 *
 * <p>docs/api/specs/02-diagnosis-recommendation.md §7 · ADR-0036.
 */
@Component
public class DiagnosisCriteriaMapper {

  private static final String DEFAULT_RECOMMENDATION_SORT = "recommended,desc";

  RecommendationCriteria toCriteria(Diagnosis d, int page, int size, String sort) {
    UniversityGroup university = d.getUniversity();
    return new RecommendationCriteria(
        d.getRegion() == null ? null : d.getRegion().name(),
        d.getMonthlyRentMin(),
        d.getMonthlyRentMax(),
        d.getConditions() == null
            ? Set.of()
            : d.getConditions().stream().map(Enum::name).collect(Collectors.toSet()),
        includedUniversityCodes(university),
        excludedUniversityCodes(university),
        d.getDistrict() == null ? null : d.getDistrict().name(),
        d.getArcStatus() == null ? null : d.getArcStatus().name(),
        page,
        size,
        sort == null || sort.isBlank() ? DEFAULT_RECOMMENDATION_SORT : sort);
  }

  /**
   * 고른 그룹의 멤버 대학 코드다 — 이 중 하나라도 인근이면 매칭된다.
   *
   * <p>{@code ETC}는 고른 학교가 목록 밖이라는 답이라 포함할 코드가 없다. {@link #excludedUniversityCodes}가 대신 답한다.
   */
  private static Set<String> includedUniversityCodes(UniversityGroup group) {
    return group == null || group == UniversityGroup.ETC ? Set.of() : group.memberCodes();
  }

  /**
   * {@code ETC}("그 외 대학")일 때만 목록 전체를 제외 조건으로 넘긴다 — 목록에 든 대학 근처 매물은 답이 될 수 없다(여집합 매칭).
   *
   * <p>두 메서드는 배타적이다. 한쪽이 값을 주면 다른 쪽은 반드시 빈 집합이며, 어기면 {@link RecommendationCriteria} 생성자가 막는다.
   */
  private static Set<String> excludedUniversityCodes(UniversityGroup group) {
    return group == UniversityGroup.ETC ? UniversityGroup.selectableMemberCodes() : Set.of();
  }
}
