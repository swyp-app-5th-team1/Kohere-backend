package com.kohere.diagnosis.domain;

/**
 * 진단 ⑥ ARC(외국인등록증) 발급 여부(단일 선택). {@code NO_ARC}(미발급)이면 추천이 ARC 불요 매물만 매칭한다 — 이 값을 스칼라 그대로 매칭 조건에
 * 넘기며, 예전처럼 {@code conditions}에 파생 코드를 넣지 않는다. docs/api/specs/02-diagnosis-recommendation.md
 * (arcStatus).
 */
public enum ArcStatus {
  ARC_ISSUED,
  NO_ARC
}
