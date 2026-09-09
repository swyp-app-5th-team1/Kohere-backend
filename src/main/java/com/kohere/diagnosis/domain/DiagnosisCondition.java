package com.kohere.diagnosis.domain;

/**
 * 진단 ④ 주거 환경 조건(다중 선택, 최대 3개). 값 이름은 listing {@code ConditionTag}와 1:1로 통일한다(cross-store 조인 없이 동일
 * enum 명세 공유). ⑥ ARC 미발급은 이 집합에 파생 코드로 들어가지 않고 {@code arcStatus} 스칼라로만 전달된다 — 그래서 {@code NO_ARC}는
 * 여기 상수로 없고, 요청에 실으면 {@code INVALID_INPUT}이다. docs/api/specs/02-diagnosis-recommendation.md
 * (conditions·arcStatus).
 */
public enum DiagnosisCondition {
  MOVE_IN_NOW,
  FEMALE_ONLY,
  MEALS_INCLUDED,
  DOUBLE_ROOM,
  PRIVATE_BATH,
  ENGLISH_OK,
  ADDRESS_REGISTRATION,
  NO_MAINT_FEE
}
