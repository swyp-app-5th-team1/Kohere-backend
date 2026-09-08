package com.kohere.diagnosis.infrastructure;

import com.kohere.diagnosis.domain.DiagnosisCondition;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

/**
 * 저장된 조건 코드 문자열 ↔ 도메인 {@link DiagnosisCondition} 변환. <b>어느 진단 어댑터도 조건 배열을 직접 옮기지 않고 이 한 벌을 쓴다.</b>
 *
 * <p><b>왜 문서가 enum이 아니라 문자열을 들고 있나.</b> 삭제된 enum 상수가 든 문서를 읽는 경로가 실제로 있다 — {@code 0004}가 {@code
 * conditions}에 백필한 {@code NO_ARC}는 그 뒤 상수가 제거됐는데 되돌린 마이그레이션이 없었다. 문서 필드가 {@code
 * Set<DiagnosisCondition>}이면 Spring Data MongoDB의 기본 변환({@code Enum.valueOf})이 <b>문서를 객체로 만드는
 * 도중</b>에 {@code IllegalArgumentException}을 던지고, 그 예외는 {@code DataAccessException}으로 번역되지 않아 그대로
 * 500이 된다. 이력 조회는 오염 문서 한 건이 페이지 전체를 죽이고, 상세·v2 추천은 {@code findById}에서 터져 404/403 게이트에 <b>닿지도
 * 못한다</b>.
 *
 * <p><b>다른 층에서는 막을 수 없다.</b> 폭발이 어댑터의 {@code toDomain}보다 앞, 문서 매핑 시점에 일어나므로 도메인 변환 지점의 필터로는 늦다.
 * {@code @ReadingConverter}도 답이 아니다 — {@code Converter}는 "원소 건너뛰기"를 표현할 수 없어 {@code null}을 돌려주면 집합에
 * {@code null} 원소가 그대로 들어가고, 그 {@code null}이 조회 응답 조립({@code List.copyOf})과 추천 조건 매핑({@code
 * Enum::name})에서 다시 NPE(500)를 만든다. 게다가 전역 등록이라 파급이 가장 넓다. 문서 타입을 저장 표현({@code 문자열})에 맞추고 어댑터에서 거르는
 * 것이 <b>실제로 작동하는 유일한 지점</b>이며 파급도 가장 작다(문서 2·어댑터 2).
 *
 * <p><b>정리 마이그레이션({@code 0124 diagnosis-v1-retire})이 있는데도 두는 이유</b>는 실패 대비가 아니다. 마이그레이션 실패는 {@code
 * runner-type: InitializingBean}이라 기동 자체를 막으므로 "실패했는데 서비스 중"이 없다. 대비하는 것은 <b>안 도는 경우</b>다 — 마이그레이션
 * 이전 덤프 복원, {@code diagnoses}만 옛 덤프로 되넣기, {@code mongock.enabled: false}(테스트 프로파일)에서는 데이터가 옛 상태로 남고
 * changelog {@code id} 기준 1회 실행이라 다시 돌지 않는다.
 *
 * <p><b>버리는 것이 데이터 손실이 아닌 근거.</b> 거르는 대상은 사용자가 고른 답이 아니다. ④ 주거 조건의 허용 선택지는 8개이고 {@code NO_ARC}는 거기
 * 없다 — 서버가 ⑥ {@code arcStatus}에서 파생해 넣던 중복 신호였다(직접 선택은 지금도 {@code INVALID_INPUT}으로 거절된다). 그 의미는
 * {@code arcStatus} 스칼라가 그대로 보존하고 추천 매칭도 그 필드로 이어진다. 응답 계약도 그대로다.
 *
 * <p>그래도 <b>조용히 삼키지는 않는다</b> — 모르는 코드는 값별로 한 번 WARN을 남긴다. 다음번 enum 축소가 같은 사고를 반복할 때 로그가 유일한 신호이기
 * 때문이고, 값별 1회로 묶는 것은 오염 문서 수만큼 같은 줄이 반복돼 신호가 묻히는 것을 막기 위해서다.
 */
@Slf4j
final class DiagnosisConditionCodes {

  /** 이미 경고한 미등록 코드. 값별 1회만 남긴다(같은 코드가 문서 수만큼 반복되면 신호가 묻힌다). */
  private static final Set<String> WARNED_CODES = ConcurrentHashMap.newKeySet();

  private DiagnosisConditionCodes() {}

  /**
   * 도메인 조건을 저장 코드로 옮긴다. 결과 BSON은 enum을 그대로 저장하던 때와 동일하다(Spring Data도 enum을 이름 문자열로 쓴다).
   *
   * <p>{@code null}은 {@code null}로 옮긴다 — 어댑터의 일은 정책이 아니라 충실한 번역이고, 빈 집합으로 바꾸면 지금 키가 없는 문서에 {@code
   * conditions: []}가 새로 기록되어 저장 모양이 달라진다.
   */
  static Set<String> toCodes(Set<DiagnosisCondition> conditions) {
    if (conditions == null) {
      return null;
    }
    Set<String> codes = new LinkedHashSet<>();
    for (DiagnosisCondition condition : conditions) {
      codes.add(condition.name());
    }
    return codes;
  }

  /**
   * 저장 코드를 도메인 조건으로 옮기고 <b>도메인이 모르는 코드는 버린다</b>. 항상 가변 {@link LinkedHashSet}을 돌려준다 — 호출부가 초안에 답을 채워
   * 넣던 기존 동작을 그대로 유지하기 위해서다.
   */
  static Set<DiagnosisCondition> toDomain(Set<String> codes) {
    Set<DiagnosisCondition> conditions = new LinkedHashSet<>();
    if (codes == null) {
      return conditions;
    }
    for (String code : codes) {
      DiagnosisCondition parsed = parse(code);
      if (parsed != null) {
        conditions.add(parsed);
      }
    }
    return conditions;
  }

  private static DiagnosisCondition parse(String code) {
    if (code == null) {
      return null;
    }
    try {
      return DiagnosisCondition.valueOf(code);
    } catch (IllegalArgumentException e) {
      warnOnce(code);
      return null;
    }
  }

  private static void warnOnce(String code) {
    if (WARNED_CODES.add(code)) {
      log.warn(
          "저장된 진단 조건 코드 '{}'가 DiagnosisCondition에 없어 무시한다 —"
              + " 0124 diagnosis-v1-retire가 돌지 않은 환경일 수 있다(같은 코드는 다시 로그하지 않는다)",
          code);
    }
  }
}
