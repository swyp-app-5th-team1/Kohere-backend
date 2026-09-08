package com.kohere.diagnosis.infrastructure.migration;

import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * 진단 문서의 {@code conditions} 배열에 남은 고아 값 {@code "NO_ARC"}를 걷어낸다.
 *
 * <p><b>왜 필요한가.</b> {@code DiagnosisCondition}에는 {@code NO_ARC} 상수가 없다. 과거 {@code 0004
 * diagnosis-condition-tag-rename}이 {@code arcStatus=ARC_PENDING} 문서에 {@code $addToSet
 * conditions:"NO_ARC"}로 백필했고 응용 계층도 ⑥ 답에서 같은 값을 파생해 넣었는데, 상수를 되돌린 커밋에는 데이터를 되돌리는 마이그레이션이 없었다. 남은
 * 문자열은 Spring Data MongoDB의 기본 enum 변환({@code Enum.valueOf})에 그대로 걸려 <b>문서를 읽는 순간</b> {@code
 * IllegalArgumentException: No enum constant …DiagnosisCondition.NO_ARC}로 터진다. 이 예외는 {@code
 * MongoExceptionTranslator}가 번역하지 않아 {@code DataAccessException}으로 감싸이지 않고, 전용 핸들러도 없어 {@code
 * GlobalExceptionHandler}의 {@code @ExceptionHandler(Exception.class)}까지 흘러 <b>500</b>이 된다.
 *
 * <p><b>어디가 깨지는가.</b> 실패는 응용 게이트가 아니라 문서→{@code DiagnosisDocument} 매핑 시점에 일어나므로 게이트로는 막을 수 없다.
 * {@code findById}로 오는 상세 조회·v2 추천 조회는 {@code status}와 무관하게 터지고, 이력 조회는 페이지에 오염 문서가 <b>한 건만</b> 섞여도
 * 커서 루프 전체가 한 try 안이라 페이지 전체가 실패한다(부분 성공이 없다). 반대로 {@code countByUserIdAndStatus}는 문서를 매핑하지 않아 멀쩡하다
 * — 같은 사용자에게 「건수는 나오는데 목록은 500」인 비대칭 증상이 나온다.
 *
 * <p><b>두 컬렉션을 함께 정리한다.</b> 같은 파생 로직이 v2 진행 세션의 초안({@code
 * diagnosisFlowSessions.draft.conditions})에도 같은 코드를 썼고, 그 컬렉션에는 TTL 인덱스가 없어 옛 세션이 그대로 남아 있을 수 있다.
 * 남아 있으면 {@code POST /api/v2/diagnoses/next}가 세션을 읽는 순간 같은 500이 난다. 두 컬렉션 모두 진단 모듈 소유라 한 유닛이 다루는 것이
 * 모듈 경계에 맞는다(ADR-0032 Decision 3).
 *
 * <p><b>{@code arcStatus} 스칼라는 건드리지 않는다.</b> {@code ArcStatus.NO_ARC}는 지금도 살아 있는 정상 값이고 추천 조건의
 * 정본이다({@code RecommendationCriteria}가 이 값을 그대로 싣고 listing이 {@code arcRequired=NOT_REQUIRED}로 읽는다).
 * {@code 0004}가 스칼라를 {@code ARC_PENDING}→{@code NO_ARC}로 옮긴 것은 지금도 옳은 이행이다. 정리 대상은 배열 원소 하나뿐이다.
 *
 * <p><b>데이터 손실이 없다.</b> {@code conditions}의 {@code NO_ARC}는 사용자가 고른 ④ 답이 아니라 ⑥ {@code arcStatus}에서
 * 서버가 파생한 중복 신호이고, 그 의미는 {@code arcStatus} 스칼라가 그대로 보존한다. 지금도 ④에 {@code NO_ARC}를 직접 고르면 {@code
 * INVALID_INPUT}으로 거부된다.
 *
 * <p><b>실행 전 실측이 필요 없다.</b> {@code $pull}은 대상이 0건이면 no-op이고 같은 값을 두 번 빼도 결과가 같아 멱등이다({@code 0123}과
 * 같은 성질). 없는 컬렉션에 대한 {@code updateMany}도 실패가 아니라 0건 갱신이라 신규 환경에서 그냥 지나간다 — 컬렉션을 만들지도 않는다. {@code
 * diagnoses}·{@code diagnosisFlowSessions}에는 {@code $jsonSchema} validator가 걸린 적이 없어 {@code 0123}이
 * 겪은 「{@code collMod} 먼저, 값 삭제 나중」 순서 제약도 없다.
 *
 * <p><b>배열이 비어도 안전하다.</b> {@code conditions}가 {@code ["NO_ARC"]} 하나뿐이던 문서는 빈 배열이 된다. 읽는 쪽은 빈 배열을 빈
 * {@code Set}으로 받고, 응답·추천 조건 변환 모두 null까지 방어하고 있다. 그래서 빈 배열을 굳이 {@code $unset}하지 않는다 — 필드를 지우면 오히려
 * null 경로로 들어간다.
 *
 * <p><b>배포 창.</b> Mongock은 {@code runner-type: InitializingBean}이라 웹 커넥터가 포트를 잡기 전에 끝나고, 실패하면 빈 생성이
 * 실패해 앱이 아예 뜨지 않는다 — 한 인스턴스 안에 「새 코드·옛 데이터」 구간이 없다. 롤링 배포로 옛 태스크가 함께 서빙하는 구간은 남지만, 옛 코드에도 {@code
 * NO_ARC} 상수가 없어 이미 같은 500을 내고 있었으므로 값을 지우는 방향은 옛 태스크에게도 이득이다.
 *
 * <p><b>이 유닛 자신은 오염에 걸리지 않는다.</b> {@code getCollection(...).updateMany(Document, Document)}는 원시
 * BSON만 다루고 {@code DiagnosisDocument}로 매핑하지 않는다 — 매핑을 태우는 {@code MongoTemplate} API를 썼다면 정리해야 할 바로
 * 그 문서에서 유닛이 터지고, {@code InitializingBean} 러너에서 그 실패는 기동 중단이다.
 *
 * <p><b>독립 유닛으로 둔다.</b> 이 정리는 v1 은퇴 작업의 일부가 아니라 <b>develop에도 이미 있는 읽기 장애</b>의 수습이라, 은퇴 유닛이 함께 하는
 * 컬렉션 드롭·인덱스 삭제와 운명을 같이할 이유가 없다. Mongock은 {@code id} 단위로 실행을 기록하므로 유닛을 나눠 두면 이 수습만 따로 앞당기거나 되짚을 수
 * 있다. 다른 유닛이 같은 {@code $pull}을 함께 들고 있어도 멱등이라 결과가 달라지지 않는다.
 *
 * <p><b>되돌리지 않는다.</b> 지운 값은 {@code arcStatus}에서 언제든 다시 파생할 수 있는 중복 신호이고, 되살리면 읽기가 다시 깨진다 —
 * forward-only(migration-policy §1).
 */
@ChangeUnit(id = "diagnosis-no-arc-condition-purge", order = "0125", author = "kohere")
public class DiagnosisNoArcConditionPurgeChangeUnit {

  private static final String DIAGNOSES = "diagnoses";
  private static final String DIAGNOSIS_FLOW_SESSIONS = "diagnosisFlowSessions";

  /**
   * 걷어낼 고아 값. {@code DiagnosisCondition}에 대응 상수가 없으므로 리터럴로 든다 — 참조할 enum이 없는 것이 이 유닛의 존재 이유다.
   * {@code ArcStatus.NO_ARC}와 이름만 같고 다른 필드의 값이다.
   */
  private static final String ORPHAN_CONDITION = "NO_ARC";

  /** 확정 진단의 조건 배열. */
  private static final String DIAGNOSES_CONDITIONS = "conditions";

  /** v2 진행 세션 초안의 조건 배열(중첩 경로). */
  private static final String SESSION_DRAFT_CONDITIONS = "draft.conditions";

  @Execution
  public void execution(MongoTemplate mongo) {
    purge(mongo, DIAGNOSES, DIAGNOSES_CONDITIONS);
    purge(mongo, DIAGNOSIS_FLOW_SESSIONS, SESSION_DRAFT_CONDITIONS);
  }

  /**
   * 해당 배열에서 고아 값을 뺀다. 필터를 두는 것은 값이 든 문서에만 쓰기를 남기기 위해서다 — {@code $pull}은 필터 없이도 결과가 같지만 컬렉션 전체를
   * 건드린다.
   */
  private static void purge(MongoTemplate mongo, String collection, String arrayField) {
    mongo
        .getCollection(collection)
        .updateMany(
            new Document(arrayField, ORPHAN_CONDITION),
            new Document("$pull", new Document(arrayField, ORPHAN_CONDITION)));
  }

  @RollbackExecution
  public void rollback(MongoTemplate mongo) {
    // 값을 되살리면 읽기가 다시 깨진다 — forward-only(migration-policy §1).
  }
}
