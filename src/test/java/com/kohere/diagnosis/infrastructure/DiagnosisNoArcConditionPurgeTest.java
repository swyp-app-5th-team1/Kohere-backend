package com.kohere.diagnosis.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.kohere.diagnosis.infrastructure.migration.DiagnosisNoArcConditionPurgeChangeUnit;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@code 0125 diagnosis-no-arc-condition-purge}가 <b>이미 저장된 문서</b>에서 고아 조건 코드 {@code "NO_ARC"}를
 * 걷어내고, 그 문서가 다시 매핑되는지 검증한다.
 *
 * <p><b>왜 이 테스트가 따로 필요한가.</b> 테스트 프로파일은 {@code mongock.enabled: false}라 마이그레이션이 어느 테스트에서도 돌지 않는다.
 * 게다가 오염 문서는 애플리케이션 코드로 <b>만들 수 없다</b> — {@code DiagnosisCondition}에 대응 상수가 없어 매핑을 통해서는 그 값을 쓸 방법이
 * 없다. 그래서 원시 {@link Document}를 {@code insertOne}으로 직접 넣어 매핑 계층을 우회한다({@code
 * ListingSchemaMigrationTest}와 같은 방식).
 *
 * <p><b>Spring 컨텍스트를 띄우지 않는다.</b> changeUnit도 문서 매핑도 {@link MongoTemplate} 하나면 되고, {@code
 * SpringBootTest}로 올리면 이 검증에 쓰지도 않는 MySQL·Redis 컨테이너가 스위트 내내 함께 살아 Docker 메모리를 밀어낸다.
 *
 * <p><b>{@code conditions}의 원소 타입에 기대지 않는다.</b> 값 자체는 원시 문서로 단정하고, 매핑은 「예외 없이 읽힌다」만 본다 — 이 유닛의 관심사는
 * 저장된 값이지 그 값을 받는 자바 타입이 아니다.
 */
@Testcontainers
class DiagnosisNoArcConditionPurgeTest {

  private static final String DIAGNOSES = "diagnoses";
  private static final String DIAGNOSIS_FLOW_SESSIONS = "diagnosisFlowSessions";
  private static final String DATABASE = "kohere-no-arc-purge-test";

  @Container static MongoDBContainer mongo = new MongoDBContainer("mongo:7.0");

  private static MongoClient client;
  private static MongoTemplate mongoTemplate;

  @BeforeAll
  static void connect() {
    client = MongoClients.create(mongo.getConnectionString());
    mongoTemplate = new MongoTemplate(client, DATABASE);
  }

  @AfterAll
  static void disconnect() {
    client.close();
  }

  @BeforeEach
  void reset() {
    mongoTemplate.getDb().drop();
  }

  @Test
  @DisplayName("0125는 확정 진단의 conditions에서 NO_ARC만 빼고 사용자가 고른 조건과 arcStatus 스칼라는 그대로 둔다")
  void purgesOrphanCodeButKeepsUserAnswersAndArcStatus() {
    insertDiagnosis(1L, List.of("MOVE_IN_NOW", "NO_ARC"));

    new DiagnosisNoArcConditionPurgeChangeUnit().execution(mongoTemplate);

    Document stored = findDiagnosis(1L);
    assertThat(stored.getList("conditions", String.class)).containsExactly("MOVE_IN_NOW");
    // ArcStatus.NO_ARC는 살아 있는 정상 값이자 추천 조건의 정본이다 — 이름이 같다고 함께 지우면 안 된다.
    assertThat(stored.getString("arcStatus")).isEqualTo("NO_ARC");
  }

  /** 정리 뒤에는 같은 문서가 예외 없이 읽혀야 한다 — 조회 500이 사라졌다는 직접 증거다. */
  @Test
  @DisplayName("정리 후 오염됐던 문서가 다시 매핑된다")
  void purgedDocumentMapsAgain() {
    insertDiagnosis(1L, List.of("MOVE_IN_NOW", "NO_ARC"));

    new DiagnosisNoArcConditionPurgeChangeUnit().execution(mongoTemplate);

    assertThatNoException()
        .isThrownBy(() -> mongoTemplate.findById(1L, DiagnosisDocument.class, DIAGNOSES));
  }

  /** 값이 하나뿐이던 배열은 비워 두고 필드를 지우지 않는다 — 지우면 오히려 null 경로로 들어간다. */
  @Test
  @DisplayName("NO_ARC뿐이던 conditions는 필드가 사라지지 않고 빈 배열로 남는다")
  void leavesEmptyArrayInsteadOfRemovingField() {
    insertDiagnosis(1L, List.of("NO_ARC"));

    new DiagnosisNoArcConditionPurgeChangeUnit().execution(mongoTemplate);

    Document stored = findDiagnosis(1L);
    assertThat(stored.containsKey("conditions")).isTrue();
    assertThat(stored.getList("conditions", String.class)).isEmpty();
    assertThatNoException()
        .isThrownBy(() -> mongoTemplate.findById(1L, DiagnosisDocument.class, DIAGNOSES));
  }

  /** 세션 초안에도 같은 파생 로직이 값을 썼고 그 컬렉션에는 TTL이 없다 — 남아 있으면 v2 세션 조회가 같은 매핑 실패를 낸다. */
  @Test
  @DisplayName("0125는 v2 진행 세션 초안(draft.conditions)도 함께 정리한다")
  void purgesOrphanCodeFromFlowSessionDraft() {
    mongoTemplate
        .getCollection(DIAGNOSIS_FLOW_SESSIONS)
        .insertOne(
            new Document("_id", "session-1")
                .append("userId", 7L)
                .append("pendingField", "arcStatus")
                .append(
                    "draft",
                    new Document("region", "SEOUL")
                        .append("conditions", List.of("NO_ARC", "ENGLISH_OK"))
                        .append("arcStatus", "NO_ARC")));

    new DiagnosisNoArcConditionPurgeChangeUnit().execution(mongoTemplate);

    Document stored = mongoTemplate.getCollection(DIAGNOSIS_FLOW_SESSIONS).find().first();
    assertThat(stored).isNotNull();
    Document draft = stored.get("draft", Document.class);
    assertThat(draft.getList("conditions", String.class)).containsExactly("ENGLISH_OK");
    assertThat(draft.getString("arcStatus")).isEqualTo("NO_ARC");
  }

  /** Mongock은 환경당 1회만 돌지만, 멱등성은 옛 덤프 복원·수동 재실행에서 실제로 쓰인다. */
  @Test
  @DisplayName("0125는 멱등이고 깨끗한 문서는 건드리지 않는다")
  void isIdempotentAndLeavesCleanDocumentsAlone() {
    insertDiagnosis(1L, List.of("MOVE_IN_NOW", "NO_ARC"));
    insertDiagnosis(2L, List.of("ENGLISH_OK"));

    new DiagnosisNoArcConditionPurgeChangeUnit().execution(mongoTemplate);
    new DiagnosisNoArcConditionPurgeChangeUnit().execution(mongoTemplate);

    assertThat(findDiagnosis(1L).getList("conditions", String.class))
        .containsExactly("MOVE_IN_NOW");
    assertThat(findDiagnosis(2L).getList("conditions", String.class)).containsExactly("ENGLISH_OK");
  }

  /** 신규 환경에는 두 컬렉션이 아직 없다 — 실패도, 빈 컬렉션 생성도 없어야 한다. */
  @Test
  @DisplayName("컬렉션이 없는 신규 환경에서도 실패하지 않고 컬렉션을 만들지도 않는다")
  void isNoOpOnFreshEnvironment() {
    assertThatNoException()
        .isThrownBy(() -> new DiagnosisNoArcConditionPurgeChangeUnit().execution(mongoTemplate));

    assertThat(mongoTemplate.collectionExists(DIAGNOSES)).isFalse();
    assertThat(mongoTemplate.collectionExists(DIAGNOSIS_FLOW_SESSIONS)).isFalse();
  }

  private static Document findDiagnosis(long id) {
    Document stored = mongoTemplate.getCollection(DIAGNOSES).find(new Document("_id", id)).first();
    assertThat(stored).isNotNull();
    return stored;
  }

  /** 애플리케이션 코드로는 만들 수 없는 문서라 원시 {@link Document}로 직접 넣는다. */
  private static void insertDiagnosis(long id, List<String> conditions) {
    mongoTemplate
        .getCollection(DIAGNOSES)
        .insertOne(
            new Document("_id", id)
                .append("userId", 42L)
                .append("region", "SEOUL")
                .append("purpose", "STUDY")
                .append("conditions", conditions)
                .append("monthlyRentMin", 300_000)
                .append("monthlyRentMax", 600_000)
                .append("arcStatus", "NO_ARC")
                .append("status", "COMPLETED")
                .append("submittedAt", Date.from(Instant.parse("2026-08-01T00:00:00Z"))));
  }
}
