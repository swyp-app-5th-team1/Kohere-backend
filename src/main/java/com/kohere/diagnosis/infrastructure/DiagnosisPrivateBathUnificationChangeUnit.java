package com.kohere.diagnosis.infrastructure;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * 개인 화장실 조건을 개인 욕실 조건으로 통합한다.
 *
 * <p>진단 enum에서 {@code PRIVATE_TOILET}을 제거하므로, 기존 카탈로그 선택지와 사용자 진단 답안의 레거시 문자열을 먼저 {@code
 * PRIVATE_BATH}로 이행한다.
 */
@ChangeUnit(id = "diagnosis-private-bath-unification", order = "0003", author = "kohere")
public class DiagnosisPrivateBathUnificationChangeUnit {

  private static final String DIAGNOSIS_QUESTIONS = "diagnosisQuestions";
  private static final String DIAGNOSES = "diagnoses";
  private static final String LEGACY_PRIVATE_TOILET = "PRIVATE_TOILET";
  private static final String PRIVATE_BATH = "PRIVATE_BATH";

  @Execution
  public void execution(MongoTemplate mongo) {
    mongo
        .getCollection(DIAGNOSIS_QUESTIONS)
        .updateMany(
            Filters.and(
                Filters.eq("field", "conditions"),
                Filters.eq("options.code", LEGACY_PRIVATE_TOILET)),
            Updates.pull("options", new Document("code", LEGACY_PRIVATE_TOILET)));

    mongo
        .getCollection(DIAGNOSES)
        .updateMany(
            Filters.eq("conditions", LEGACY_PRIVATE_TOILET),
            Updates.addToSet("conditions", PRIVATE_BATH));
    mongo
        .getCollection(DIAGNOSES)
        .updateMany(
            Filters.eq("conditions", LEGACY_PRIVATE_TOILET),
            Updates.pull("conditions", LEGACY_PRIVATE_TOILET));
  }

  /** forward-only: enum 통합 이행은 되돌리지 않는다. */
  @RollbackExecution
  public void rollback() {
    // no-op (forward-only)
  }
}
