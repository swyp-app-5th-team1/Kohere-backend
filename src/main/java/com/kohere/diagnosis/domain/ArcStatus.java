package com.kohere.diagnosis.domain;

/**
 * 진단 ⑥ ARC(외국인등록증) 발급 여부(단일 선택). {@code NO_ARC}(미발급)이면 서버가 파생 조건 {@link DiagnosisCondition#NO_ARC}를
 * 추천 조건에 더해 ARC 불요 매물만 매칭한다. docs/api/specs/02-diagnosis-recommendation.md (arcStatus).
 */
public enum ArcStatus {
  ARC_ISSUED,
  NO_ARC
}
