package com.kohere.diagnosis.domain;

/**
 * 진단 ④ 주거 환경 조건(다중 선택, 최대 3개) 8종. 값 이름은 listing {@code ConditionTag}와 동일하게 통일한다(cross-store 조인 없이
 * 동일 enum 명세 공유). docs/api/specs/02-diagnosis-recommendation.md (conditions).
 */
public enum DiagnosisCondition {
  IMMEDIATE_MOVE_IN,
  FEMALE_ONLY,
  PRIVATE_BATH,
  ENGLISH_AVAILABLE,
  RESIDENT_REGISTRATION,
  NO_MAINTENANCE_FEE,
  MEALS_PROVIDED,
  DOUBLE_ROOM
}
