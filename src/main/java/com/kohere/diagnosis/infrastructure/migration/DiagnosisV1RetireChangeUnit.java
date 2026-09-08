package com.kohere.diagnosis.infrastructure.migration;

import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import java.time.Instant;
import java.util.Date;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * v1 진단 경로가 남긴 저장소 잔해를 걷어낸다 — 조정 제안 컬렉션, 죽은 문항 인덱스, 고아 조건 코드, 그리고 이어질 수 없게 된 진행 중 초안.
 *
 * <p><b>고아 조건 코드({@code NO_ARC})를 뺀다.</b> 그 상수를 추가하며 백필했던 마이그레이션이 나중에 상수만 되돌리고 데이터는 두고 갔다(운영 실측
 * 40건). 지금 도메인 {@code DiagnosisCondition}에는 그 값이 없다.
 *
 * <p><b>값을 빼는 것이지 문서를 지우는 것이 아니다.</b> 진단 이력은 사용자가 홈에서 "재진단" 분기를 보고 지난 결과를 다시 보는 데 쓰이므로, 코드 하나 때문에
 * 레코드를 버리지 않는다. 미등록 값이 읽기를 깨뜨리지도 않는다 — 드라이버가 그 원소만 빼고 읽으므로 응답에서 사라질 뿐이다. 그래도 저장된 값을 정리하는 이유는, 도메인이
 * 모르는 값이 남아 있으면 집계·이관 같은 후속 작업이 매번 그것을 다시 만나기 때문이다.
 *
 * <p><b>{@code arcStatus} 스칼라는 건드리지 않는다.</b> {@code ArcStatus.NO_ARC}는 살아 있는 정상 값이고 추천이 ARC 불요 매물을
 * 좁히는 근거다 — 배열의 문자열과 이름만 같을 뿐 다른 것이다.
 *
 * <p><b>진행 세션의 초안({@code draft.conditions})에도 같은 값이 있다.</b> 세션은 {@code /start}가 갈아끼우니 스스로 낫는다고 볼 수도
 * 있지만, 그것은 사용자가 진단을 <b>다시 시작할 때만</b> 참이다 — 이어서 하려는 사용자의 초안은 그대로 남는다. 그래서 두 컬렉션을 함께 정리한다.
 *
 * <p><b>이어질 수 없게 된 {@code IN_PROGRESS}는 닫는다.</b> 그 초안을 채우던 v1 답 저장 경로가 사라져 완성시킬 방법이 없다. 상태만 바꾸지 않고
 * {@code submittedAt}(도메인이 정의한 "종료 시각")을 함께 채운다 — 종료 시각 없는 종료 문서를 남기지 않는다. 확정 문서는 건드리지 않는다.
 *
 * <p><b>인덱스는 하나만 지운다.</b> {@code diagnoses}의 {@code (userId, submittedAt)}는 이력·최근 조회가 계속 타므로 남기고,
 * {@code diagnosisQuestions}의 {@code (active, step)}만 지운다 — 문항 문서에 {@code step} 필드가 없어 v1 제거 이전부터
 * 아무 질의도 타지 않던 인덱스다.
 *
 * <p><b>{@code 0125}와 고아 코드 정리가 겹치는 것은 의도다.</b> 이 유닛은 "v1 은퇴"를 한 덩어리로 끝내고, {@code 0125}는 같은 정리를 자기
 * 이유로 다시 보장한다 — 둘 다 {@code $pull}이라 나중에 도는 쪽은 대상 0건으로 지나간다. 겹친다고 한쪽을 지우면 그 유닛의 계약 테스트가 깨진다.
 *
 * <p><b>재실행 안전이 계약이다.</b> {@code transactional: false}라 중간에 실패하면 유닛 전체가 다음 기동에 다시 돈다. {@code
 * $pull}과 상태 이행은 멱등이고 {@code updateMany}는 없는 컬렉션을 만들지 않으므로, 신규 환경에서도 사전 실측 없이 그냥 통과한다.
 *
 * <p><b>되돌리지 않는다.</b> {@code @RollbackExecution}은 no-op이다 — forward-only(migration-policy §1).
 */
@ChangeUnit(id = "diagnosis-v1-retire", order = "0124", author = "kohere")
public class DiagnosisV1RetireChangeUnit {

  private static final String DIAGNOSES = "diagnoses";
  private static final String FLOW_SESSIONS = "diagnosisFlowSessions";
  private static final String DIAGNOSIS_QUESTIONS = "diagnosisQuestions";
  private static final String DIAGNOSIS_SUGGESTIONS = "diagnosisSuggestions";
  private static final String DEAD_QUESTION_INDEX = "active_step_idx";

  /** 도메인 {@code DiagnosisCondition}에서 사라진 코드. 배열 원소로만 나타나며 {@code arcStatus} 스칼라와는 무관하다. */
  private static final String ORPHAN_CONDITION = "NO_ARC";

  @Execution
  public void execution(MongoTemplate mongo) {
    // 조정 제안은 v1 추천이 0건일 때만 쓰던 자산이다. 그 경로가 사라져 읽는 코드가 0이 됐다.
    if (mongo.collectionExists(DIAGNOSIS_SUGGESTIONS)) {
      mongo.getCollection(DIAGNOSIS_SUGGESTIONS).drop();
    }

    // 확정·폐기 진단의 조건 배열에서 고아 코드를 뺀다.
    mongo
        .getCollection(DIAGNOSES)
        .updateMany(
            new Document("conditions", ORPHAN_CONDITION),
            new Document("$pull", new Document("conditions", ORPHAN_CONDITION)));

    // 진행 세션 초안에도 같은 값이 들어 있다 — 이어서 진단하려는 사용자의 초안이다.
    mongo
        .getCollection(FLOW_SESSIONS)
        .updateMany(
            new Document("draft.conditions", ORPHAN_CONDITION),
            new Document("$pull", new Document("draft.conditions", ORPHAN_CONDITION)));

    // 완성시킬 코드 경로가 사라진 초안을 종료 시각과 함께 닫는다.
    mongo
        .getCollection(DIAGNOSES)
        .updateMany(
            new Document("status", "IN_PROGRESS"),
            new Document(
                "$set",
                new Document("status", "DISCARDED")
                    .append("submittedAt", Date.from(Instant.now()))));

    dropDeadQuestionIndex(mongo);
  }

  /**
   * 인덱스가 이미 없으면 드라이버가 {@code IndexNotFound}로 던진다. 신규 환경에서는 만들어진 적이 없어 없는 것이 정상이고, 재실행 때도 두 번째부터는 없다
   * — 그 때문에 마이그레이션 전체가 실패해 기동이 멈추면 안 된다.
   */
  private static void dropDeadQuestionIndex(MongoTemplate mongo) {
    if (!mongo.collectionExists(DIAGNOSIS_QUESTIONS)) {
      return;
    }
    try {
      mongo.getCollection(DIAGNOSIS_QUESTIONS).dropIndex(DEAD_QUESTION_INDEX);
    } catch (RuntimeException e) {
      // 없으면 지울 것도 없다.
    }
  }

  @RollbackExecution
  public void rollback(MongoTemplate mongo) {
    // 되돌리지 않는다 — forward-only(migration-policy §1).
  }
}
