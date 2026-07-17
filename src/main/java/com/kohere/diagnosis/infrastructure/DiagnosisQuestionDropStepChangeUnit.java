package com.kohere.diagnosis.infrastructure;

import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * 문항 카탈로그({@code diagnosisQuestions})에서 {@code step} 필드를 걷어낸다(order 0006).
 *
 * <p>카탈로그는 <b>문항의 표현만</b> 담고 순서·단계 번호는 코드({@code DiagnosisFlowStep})가 갖기로 했다(ADR-0036) — 문항은 {@code
 * field}로 유일하게 식별되므로 저장된 {@code step}은 쓰이지 않는 잔재이고, 남겨두면 "카탈로그가 순서를 안다"는 오해를 부른다. 이미 배포된 환경의 도큐먼트에서
 * 제거한다(신규 환경은 order 0000 시드가 애초에 넣지 않아 대상이 0건 — 멱등).
 *
 * <p>{@code (active, step)} 복합 인덱스도 함께 지운다. 새 조회 키인 {@code field} UNIQUE 인덱스는 부트스트랩 initializer가
 * 보장한다 (인덱스=부트스트랩, 시드·데이터 진화만 Mongock — [migration-policy §8]·ADR-0032).
 */
@Slf4j
@ChangeUnit(id = "diagnosis-question-drop-step", order = "0006", author = "kohere")
public class DiagnosisQuestionDropStepChangeUnit {

  private static final String OLD_INDEX = "active_step_idx";

  @Execution
  public void execution(MongoTemplate mongo) {
    mongo.updateMulti(new Query(), new Update().unset("step"), DiagnosisQuestionDocument.class);
    try {
      mongo.indexOps(DiagnosisQuestionDocument.class).dropIndex(OLD_INDEX);
    } catch (RuntimeException e) {
      // 신규 환경엔 애초에 없다 — 없다고 마이그레이션을 실패시키지 않는다.
      log.info("기존 {} 인덱스가 없어 건너뛴다: {}", OLD_INDEX, e.getMessage());
    }
  }

  /** forward-only: 되돌리지 않는다(Mongock이 메서드 존재만 요구). */
  @RollbackExecution
  public void rollback() {
    // no-op (forward-only)
  }
}
