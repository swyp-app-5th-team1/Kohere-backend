package com.kohere.diagnosis.domain;

import java.util.List;

/**
 * 진단 문항 카탈로그 포트(도메인). 구현은 infrastructure의 MongoDB({@code diagnosisQuestions}) 어댑터가 제공한다. 데이터만 보유하며
 * 분기 메타는 두지 않는다 — 단계(step)로 문항을 조회하고, ③(step 3)처럼 한 단계에 문항이 둘(university·district)이면 어느 것을 낼지는 응용
 * 서비스가 저장된 {@code purpose}로 결정한다(ADR-0028).
 */
public interface DiagnosisQuestionCatalog {

  /** 단계(step)의 활성 문항 목록 조회. 대부분 1건, ③(step 3)은 university·district 2건이 올 수 있다. */
  List<DiagnosisQuestion> findByStep(int step);
}
