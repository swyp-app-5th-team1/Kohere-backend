package com.kohere.diagnosis.infrastructure;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/** 진단 문항 카탈로그 Spring Data MongoDB 리포지토리(infrastructure 내부). */
interface DiagnosisQuestionMongoRepository
    extends MongoRepository<DiagnosisQuestionDocument, String> {

  /** {@code field}는 카탈로그 전체에서 유일하다(UNIQUE 인덱스) — 활성 문항 1건. */
  Optional<DiagnosisQuestionDocument> findByFieldAndActiveTrue(String field);
}
