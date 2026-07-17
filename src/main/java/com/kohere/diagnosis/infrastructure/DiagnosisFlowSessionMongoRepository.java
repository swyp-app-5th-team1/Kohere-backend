package com.kohere.diagnosis.infrastructure;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/** v2 진행 세션 Spring Data MongoDB 리포지토리(infrastructure 내부). 어댑터에서만 사용한다. */
interface DiagnosisFlowSessionMongoRepository
    extends MongoRepository<DiagnosisFlowSessionDocument, String> {

  Optional<DiagnosisFlowSessionDocument> findByUserId(long userId);

  void deleteByUserId(long userId);
}
