package com.kohere.diagnosis.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.kohere.diagnosis.domain.Diagnosis;
import com.kohere.diagnosis.domain.DiagnosisCondition;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * <b>정리 마이그레이션이 돌지 않은 환경</b>에서도 진단 어댑터가 살아 있는지 검증한다 — {@code 0124 diagnosis-v1-retire}의 심층 방어 짝이다.
 *
 * <p>이 슬라이스는 {@code mongock.enabled=false}라 changeUnit이 <b>절대 돌지 않는다</b>. 그래서 여기서 심는 {@code
 * conditions:["PRIVATE_BATH","NO_ARC"]}는 옛 덤프를 복원했거나 {@code diagnoses}만 되넣은 환경의 모습 그대로이고, 방어가 없으면
 * 문서를 객체로 만드는 도중 {@code IllegalArgumentException}이 나 조회 전체가 500이 된다.
 *
 * <p>문서는 반드시 원시 {@link Document}로 심는다 — 도메인 빌더로 심으면 {@code NO_ARC}가 enum에 없어 컴파일이 막혀 이 상황을 재현할 수
 * <b>없다</b>. 그래서 이 케이스는 매핑 계층을 우회하는 이 방식으로만 지킬 수 있다.
 */
@DataMongoTest
@Testcontainers
@TestPropertySource(properties = "mongock.enabled=false")
@Import({
  DiagnosisRepositoryImpl.class,
  DiagnosisFlowSessionRepositoryImpl.class,
  SequenceGenerator.class
})
class DiagnosisLegacyConditionCodeTest {

  private static final long USER_ID = 42L;
  private static final Date SUBMITTED_AT = Date.from(Instant.parse("2026-08-01T00:00:00Z"));

  @Container @ServiceConnection static MongoDBContainer mongo = new MongoDBContainer("mongo:7.0");

  @Autowired DiagnosisRepositoryImpl diagnosisRepository;
  @Autowired DiagnosisFlowSessionRepositoryImpl flowSessionRepository;
  @Autowired MongoTemplate mongoTemplate;

  @BeforeEach
  void reset() {
    mongoTemplate.getCollection("diagnoses").drop();
    mongoTemplate.getCollection("diagnosisFlowSessions").drop();
  }

  @Test
  @DisplayName("삭제된 조건 코드가 든 확정 진단도 상세·최근·이력이 모두 읽힌다")
  void readsCompletedDiagnosisCarryingRemovedConditionCode() {
    insertLegacyDiagnosis(950L);

    Optional<Diagnosis> detail = diagnosisRepository.findById(950L);

    assertThat(detail).isPresent();
    assertThat(detail.get().getConditions()).containsExactly(DiagnosisCondition.PRIVATE_BATH);
    // 버리는 것은 파생 신호일 뿐이고 그 의미는 arcStatus 스칼라가 그대로 보존한다 — 추천 매칭이 그 필드로 이어진다.
    assertThat(detail.get().getArcStatus().name()).isEqualTo("NO_ARC");

    assertThat(diagnosisRepository.findLatestCompletedByUserId(USER_ID)).isPresent();
    assertThat(diagnosisRepository.findCompletedByUserId(USER_ID, 0, 10, false)).hasSize(1);
  }

  @Test
  @DisplayName("오염 문서 한 건이 이력 페이지 전체를 죽이지 않는다")
  void oneLegacyDocumentDoesNotKillTheWholeHistoryPage() {
    // 매핑은 커서 루프 전체가 한 try 안이라, 방어가 없으면 정상 문서까지 통째로 버려지고 0건도 못 돌려준다.
    insertLegacyDiagnosis(951L);
    diagnosisRepository.save(
        Diagnosis.startInProgress(USER_ID).toBuilder()
            .region(com.kohere.diagnosis.domain.Region.SEOUL)
            .purpose(com.kohere.diagnosis.domain.Purpose.STUDY)
            .university(com.kohere.diagnosis.domain.UniversityGroup.SNU_CAU_SOONGSIL)
            .conditions(new java.util.LinkedHashSet<>(List.of(DiagnosisCondition.ENGLISH_OK)))
            .monthlyRentMin(300000)
            .monthlyRentMax(600000)
            .arcStatus(com.kohere.diagnosis.domain.ArcStatus.ARC_ISSUED)
            .build()
            .complete(Instant.parse("2026-08-02T00:00:00Z")));

    List<Diagnosis> page = diagnosisRepository.findCompletedByUserId(USER_ID, 0, 10, false);

    assertThat(page).hasSize(2);
    assertThat(diagnosisRepository.countCompletedByUserId(USER_ID)).isEqualTo(2);
  }

  @Test
  @DisplayName("삭제된 조건 코드가 든 v2 진행 세션도 읽힌다")
  void readsFlowSessionDraftCarryingRemovedConditionCode() {
    mongoTemplate
        .getCollection("diagnosisFlowSessions")
        .insertOne(
            new Document("userId", USER_ID)
                .append("pendingField", "arcStatus")
                .append(
                    "draft",
                    new Document("region", "SEOUL")
                        .append("conditions", List.of("NO_ARC", "ENGLISH_OK"))));

    var session = flowSessionRepository.findByUserId(USER_ID);

    assertThat(session).isPresent();
    assertThat(session.get().getDraft().getConditions())
        .containsExactly(DiagnosisCondition.ENGLISH_OK);
  }

  @Test
  @DisplayName("되쓰기는 걸러진 값을 되살리지 않는다")
  void rewritingDoesNotResurrectTheDroppedCode() {
    insertLegacyDiagnosis(952L);

    Diagnosis read = diagnosisRepository.findById(952L).orElseThrow();
    diagnosisRepository.save(read);

    Document stored =
        mongoTemplate.getCollection("diagnoses").find(new Document("_id", 952L)).first();
    assertThat(stored).isNotNull();
    assertThat(stored.getList("conditions", String.class)).containsExactly("PRIVATE_BATH");
  }

  private void insertLegacyDiagnosis(long id) {
    mongoTemplate
        .getCollection("diagnoses")
        .insertOne(
            new Document("_id", id)
                .append("userId", USER_ID)
                .append("region", "SEOUL")
                .append("purpose", "STUDY")
                .append("university", "SNU_CAU_SOONGSIL")
                .append("conditions", List.of("PRIVATE_BATH", "NO_ARC"))
                .append("monthlyRentMin", 300000)
                .append("monthlyRentMax", 600000)
                .append("arcStatus", "NO_ARC")
                .append("status", "COMPLETED")
                .append("submittedAt", SUBMITTED_AT));
  }
}
