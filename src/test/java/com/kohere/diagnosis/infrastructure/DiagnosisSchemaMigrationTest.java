package com.kohere.diagnosis.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.kohere.diagnosis.infrastructure.migration.DiagnosisV1RetireChangeUnit;
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
 * {@code 0124 diagnosis-v1-retire}가 <b>이미 저장된 문서</b>를 실제로 고치는지 검증한다.
 *
 * <p><b>왜 필요한가.</b> 테스트 프로파일은 {@code mongock.enabled: false}라 마이그레이션이 어느 테스트에서도 돌지 않는다. 검증이 없으면
 * {@code $pull}이 통째로 빠져도, 필드 경로를 {@code conditions} 대신 {@code draft.conditions}로 잘못 적어도 빌드는 초록불이고
 * 배포 뒤 조회가 500을 내야 드러난다.
 *
 * <p><b>Spring 컨텍스트를 띄우지 않는다</b> — {@link ListingSchemaMigrationTest}와 같은 이유로 {@link MongoTemplate}
 * 하나만 만들어 changeUnit의 {@code execution}을 직접 부른다. 문서도 매핑 계층을 우회해 원시 {@link Document}로 넣는다 — 도메인 빌더로
 * 심으면 {@code NO_ARC}가 enum에 없어 <b>컴파일이 막혀</b> 이 상황을 재현할 수 없다.
 */
@Testcontainers
class DiagnosisSchemaMigrationTest {

  private static final String DATABASE = "kohere-diagnosis-migration-test";
  private static final String DIAGNOSES = "diagnoses";
  private static final String FLOW_SESSIONS = "diagnosisFlowSessions";
  private static final String SUGGESTIONS = "diagnosisSuggestions";
  private static final String QUESTIONS = "diagnosisQuestions";

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
  @DisplayName("0124는 확정 진단의 고아 조건 코드(NO_ARC)만 빼고 나머지 조건과 arcStatus 스칼라는 그대로 둔다")
  void pullsOrphanConditionCodeFromDiagnoses() {
    mongoTemplate
        .getCollection(DIAGNOSES)
        .insertOne(completedDiagnosis(900L, List.of("PRIVATE_BATH", "NO_ARC")));

    new DiagnosisV1RetireChangeUnit().execution(mongoTemplate);

    Document stored = mongoTemplate.getCollection(DIAGNOSES).find().first();
    assertThat(stored).isNotNull();
    assertThat(stored.getList("conditions", String.class)).containsExactly("PRIVATE_BATH");
    // 스칼라는 살아 있는 정상 값이다 — 여기까지 지우면 추천에서 ARC 불요 매칭이 사라진다.
    assertThat(stored.getString("arcStatus")).isEqualTo("NO_ARC");
  }

  @Test
  @DisplayName("0124는 v2 진행 세션 초안(draft.conditions)의 고아 조건 코드도 뺀다")
  void pullsOrphanConditionCodeFromFlowSessionDrafts() {
    mongoTemplate
        .getCollection(FLOW_SESSIONS)
        .insertOne(
            new Document("userId", 7L)
                .append("pendingField", "arcStatus")
                .append(
                    "draft",
                    new Document("region", "SEOUL")
                        .append("conditions", List.of("NO_ARC", "ENGLISH_OK"))));

    new DiagnosisV1RetireChangeUnit().execution(mongoTemplate);

    Document stored = mongoTemplate.getCollection(FLOW_SESSIONS).find().first();
    assertThat(stored).isNotNull();
    assertThat(stored.get("draft", Document.class).getList("conditions", String.class))
        .containsExactly("ENGLISH_OK");
  }

  @Test
  @DisplayName("0124는 이어질 수 없게 된 IN_PROGRESS를 종료 시각과 함께 DISCARDED로 닫고 COMPLETED는 건드리지 않는다")
  void closesAbandonedDrafts() {
    mongoTemplate
        .getCollection(DIAGNOSES)
        .insertOne(new Document("_id", 901L).append("userId", 1L).append("status", "IN_PROGRESS"));
    mongoTemplate.getCollection(DIAGNOSES).insertOne(completedDiagnosis(902L, List.of()));

    new DiagnosisV1RetireChangeUnit().execution(mongoTemplate);

    Document closed = findById(901L);
    assertThat(closed.getString("status")).isEqualTo("DISCARDED");
    // 도메인이 submittedAt을 "종료 시각"으로 정의한다 — 상태만 바꾸면 종료 시각 없는 종료 문서가 남는다.
    assertThat(closed.getDate("submittedAt")).isNotNull();

    Document untouched = findById(902L);
    assertThat(untouched.getString("status")).isEqualTo("COMPLETED");
    assertThat(untouched.getDate("submittedAt")).isEqualTo(SUBMITTED_AT);
  }

  @Test
  @DisplayName("0124는 v1 조정 제안 컬렉션을 드롭하고 죽은 문항 인덱스를 지운다")
  void dropsRetiredSuggestionCatalogAndObsoleteQuestionIndex() {
    mongoTemplate.getCollection(SUGGESTIONS).insertOne(new Document("_id", "NO_MATCH"));
    mongoTemplate.getCollection(QUESTIONS).insertOne(new Document("field", "region"));
    mongoTemplate
        .getCollection(QUESTIONS)
        .createIndex(new Document("active", 1).append("step", 1), indexNamed("active_step_idx"));

    new DiagnosisV1RetireChangeUnit().execution(mongoTemplate);

    assertThat(mongoTemplate.collectionExists(SUGGESTIONS)).isFalse();
    assertThat(indexNamesOfQuestions()).doesNotContain("active_step_idx");
    // 문항 자체는 v2가 계속 쓴다 — 인덱스만 지운다.
    assertThat(mongoTemplate.getCollection(QUESTIONS).countDocuments()).isEqualTo(1);
  }

  @Test
  @DisplayName("0124는 빈 DB에서도, 두 번 돌려도 실패하지 않는다")
  void isIdempotentAndSafeOnFreshEnvironments() {
    // transactional:false라 중간 실패 시 유닛 전체가 다음 기동에 다시 돈다 — 재실행 안전성이 계약이다.
    assertThatNoException()
        .isThrownBy(() -> new DiagnosisV1RetireChangeUnit().execution(mongoTemplate));
    // updateMany는 없는 컬렉션을 만들지 않는다 — 그래서 사전 실측 없이 새 환경에서도 그냥 통과한다.
    assertThat(mongoTemplate.collectionExists(DIAGNOSES)).isFalse();
    assertThat(mongoTemplate.collectionExists(FLOW_SESSIONS)).isFalse();

    mongoTemplate
        .getCollection(DIAGNOSES)
        .insertOne(completedDiagnosis(903L, List.of("NO_ARC", "PRIVATE_BATH")));
    new DiagnosisV1RetireChangeUnit().execution(mongoTemplate);
    new DiagnosisV1RetireChangeUnit().execution(mongoTemplate);

    assertThat(findById(903L).getList("conditions", String.class)).containsExactly("PRIVATE_BATH");
  }

  private static final Date SUBMITTED_AT = Date.from(Instant.parse("2026-08-01T00:00:00Z"));

  private static Document completedDiagnosis(long id, List<String> conditions) {
    return new Document("_id", id)
        .append("userId", 1L)
        .append("region", "SEOUL")
        .append("conditions", conditions)
        .append("arcStatus", "NO_ARC")
        .append("status", "COMPLETED")
        .append("submittedAt", SUBMITTED_AT);
  }

  private static Document findById(long id) {
    Document found = mongoTemplate.getCollection(DIAGNOSES).find(new Document("_id", id)).first();
    assertThat(found).isNotNull();
    return found;
  }

  private static com.mongodb.client.model.IndexOptions indexNamed(String name) {
    return new com.mongodb.client.model.IndexOptions().name(name);
  }

  private static List<String> indexNamesOfQuestions() {
    return mongoTemplate
        .getCollection(QUESTIONS)
        .listIndexes()
        .into(new java.util.ArrayList<>())
        .stream()
        .map(index -> index.getString("name"))
        .toList();
  }
}
