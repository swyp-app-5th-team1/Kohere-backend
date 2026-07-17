package com.kohere.diagnosis.domain;

import java.util.Optional;

/**
 * 진단 문항 카탈로그 포트(도메인). 구현은 infrastructure의 MongoDB({@code diagnosisQuestions}) 어댑터가 제공한다.
 *
 * <p><b>문항의 표현(문구·선택지·번역)만 보유하고 순서·분기 메타는 두지 않는다</b> — 진행 순서와 단계 번호는 {@link DiagnosisFlowStep}이,
 * ③처럼 한 단계에서 어느 문항을 낼지는 응용 서비스가 저장된 {@code purpose}로 정한다(ADR-0028·ADR-0036). 그래서 조회 키는 단계가 아니라 문항을
 * 유일하게 식별하는 {@code field}다.
 */
public interface DiagnosisQuestionCatalog {

  /** 활성 문항 1건 조회({@code field}는 카탈로그 전체에서 유일). 없으면 empty. */
  Optional<DiagnosisQuestion> findByField(String field);
}
