package com.kohere.diagnosis.infrastructure;

import lombok.Getter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * 진단 id 시퀀스 카운터 도큐먼트({@code diagnosisSequences}). MongoDB에는 자동 증가 Long id가 없어 원자적 {@code $inc}로 순번을
 * 발급한다(스펙의 숫자 {@code diagnosisId} 계약 유지).
 */
@Getter
@Document(collection = "diagnosisSequences")
class DiagnosisSequence {

  @Id private String id;
  private long seq;
}
