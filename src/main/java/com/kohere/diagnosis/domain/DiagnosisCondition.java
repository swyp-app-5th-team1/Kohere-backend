package com.kohere.diagnosis.domain;

/**
 * 진단 ④ 주거 환경 조건(다중 선택, 최대 3개)과 ⑥ ARC 미발급 파생 조건 {@code NO_ARC}. 값 이름은 listing {@code ConditionTag}와
 * 1:1로 통일한다(cross-store 조인 없이 동일 enum 명세 공유). {@code NO_ARC}는 ⑥ {@code arcStatus}가 {@code
 * ArcStatus.NO_ARC}(미발급)일 때 서버가 파생해 넣는 매물 정책 필터라 ④ 선택지에는 없고 사용자가 직접 고를 수 없다.
 * docs/api/specs/02-diagnosis-recommendation.md (conditions·arcStatus).
 */
public enum DiagnosisCondition {
  MOVE_IN_NOW,
  FEMALE_ONLY,
  MEALS_INCLUDED,
  DOUBLE_ROOM,
  PRIVATE_BATH,
  ENGLISH_OK,
  ADDRESS_REGISTRATION,
  NO_MAINT_FEE,
  NO_ARC;

  /** ④에서 사용자가 직접 선택할 수 있는 주거 조건인지(⑥에서 파생하는 {@code NO_ARC}는 제외). */
  public boolean userSelectable() {
    return this != NO_ARC;
  }
}
