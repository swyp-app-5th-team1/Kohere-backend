package com.kohere.diagnosis.infrastructure;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import java.util.List;
import java.util.Map;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * 진단 ④ 주거 조건 코드를 listing {@code ConditionTag}의 새 UI 필터명으로 통일하고, ⑥ ARC 상태 값을 {@code ARC_PENDING}에서
 * {@code NO_ARC}로 통일하며, ARC 미발급 진단에 파생 조건 {@code NO_ARC}를 백필한다(order 0004).
 *
 * <p>이미 배포된 환경에는 옛 코드가 카탈로그({@code diagnosisQuestions})와 사용자 진단({@code diagnoses})에 남아 있으므로, 옛 코드를
 * 새 코드로 이행한다(④ rename 매핑은 listing {@code ListingConditionTagRenameChangeUnit}과 동일). 신규 환경은 order
 * 0000 시드가 이미 새 코드를 적재하므로 이 이행은 no-op가 된다.
 *
 * <p>⑥ ARC 상태는 옛 값 {@code ARC_PENDING}(미발급)을 {@code NO_ARC}로 리네임한다 — 사용자 진단의 {@code arcStatus} 스칼라
 * 값과 카탈로그 선택지 코드를 함께 이행한다. 또한 미발급 진단은 응용 계층이 답 저장 시 {@code conditions}에 {@code NO_ARC}를 파생 저장하도록
 * 바뀌었으므로, 기존 진단에도 동일 규칙을 소급 적용해 추천 조건 정합을 맞춘다(arcStatus 리네임 전에 옛 값 기준으로 백필).
 */
@ChangeUnit(id = "diagnosis-condition-tag-rename", order = "0004", author = "kohere")
public class DiagnosisConditionTagRenameChangeUnit {

  private static final String DIAGNOSES = "diagnoses";
  private static final String DIAGNOSIS_QUESTIONS = "diagnosisQuestions";
  private static final String NO_ARC = "NO_ARC";
  private static final String ARC_PENDING = "ARC_PENDING";

  private static final Map<String, String> RENAMES =
      Map.of(
          "IMMEDIATE_MOVE_IN", "MOVE_IN_NOW",
          "ENGLISH_AVAILABLE", "ENGLISH_OK",
          "RESIDENT_REGISTRATION", "ADDRESS_REGISTRATION",
          "NO_MAINTENANCE_FEE", "NO_MAINT_FEE",
          "MEALS_PROVIDED", "MEALS_INCLUDED");

  @Execution
  public void execution(MongoTemplate mongo) {
    RENAMES.forEach(
        (legacy, replacement) -> {
          renameDiagnosisCondition(mongo, legacy, replacement);
          renameCatalogOptionCode(mongo, "conditions", legacy, replacement);
        });
    // arcStatus 리네임 전에 옛 값(ARC_PENDING) 기준으로 파생 NO_ARC 조건을 먼저 백필한다.
    backfillNoArc(mongo);
    renameArcStatus(mongo);
  }

  /** 사용자 진단 문서의 {@code conditions} 배열 값을 옛 코드→새 코드로 치환한다(중복 없이 addToSet 후 pull). */
  private static void renameDiagnosisCondition(
      MongoTemplate mongo, String legacy, String replacement) {
    mongo
        .getCollection(DIAGNOSES)
        .updateMany(Filters.eq("conditions", legacy), Updates.addToSet("conditions", replacement));
    mongo
        .getCollection(DIAGNOSES)
        .updateMany(Filters.eq("conditions", legacy), Updates.pull("conditions", legacy));
  }

  /** 지정한 {@code field} 문항 카탈로그 선택지의 {@code options[].code}를 옛 코드→새 코드로 치환한다. */
  private static void renameCatalogOptionCode(
      MongoTemplate mongo, String field, String legacy, String replacement) {
    UpdateOptions options =
        new UpdateOptions().arrayFilters(List.of(Filters.eq("opt.code", legacy)));
    mongo
        .getCollection(DIAGNOSIS_QUESTIONS)
        .updateMany(
            Filters.and(Filters.eq("field", field), Filters.eq("options.code", legacy)),
            Updates.set("options.$[opt].code", replacement),
            options);
  }

  /** ARC 미발급(옛 arcStatus 값 ARC_PENDING) 진단에 파생 조건 {@code NO_ARC}를 소급 추가한다(중복 없이 addToSet). */
  private static void backfillNoArc(MongoTemplate mongo) {
    mongo
        .getCollection(DIAGNOSES)
        .updateMany(Filters.eq("arcStatus", ARC_PENDING), Updates.addToSet("conditions", NO_ARC));
  }

  /** ⑥ arcStatus 스칼라 값과 선택지 코드를 옛 {@code ARC_PENDING}에서 {@code NO_ARC}로 통일한다. */
  private static void renameArcStatus(MongoTemplate mongo) {
    mongo
        .getCollection(DIAGNOSES)
        .updateMany(Filters.eq("arcStatus", ARC_PENDING), Updates.set("arcStatus", NO_ARC));
    renameCatalogOptionCode(mongo, "arcStatus", ARC_PENDING, NO_ARC);
  }

  /** forward-only: enum 문자열 이행·백필은 되돌리지 않는다(Mongock이 메서드 존재만 요구). */
  @RollbackExecution
  public void rollback() {
    // no-op (forward-only)
  }
}
