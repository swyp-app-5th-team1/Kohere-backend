package com.kohere.diagnosis.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kohere.common.exception.InvalidInputException;
import com.kohere.diagnosis.application.DiagnosisQueryService;
import com.kohere.diagnosis.application.dto.DiagnosisResponse;
import com.kohere.diagnosis.domain.ArcStatus;
import com.kohere.diagnosis.domain.Diagnosis;
import com.kohere.diagnosis.domain.DiagnosisAccessDeniedException;
import com.kohere.diagnosis.domain.DiagnosisCondition;
import com.kohere.diagnosis.domain.DiagnosisNotFoundException;
import com.kohere.diagnosis.domain.DiagnosisRepository;
import com.kohere.diagnosis.domain.Purpose;
import com.kohere.diagnosis.domain.Region;
import com.kohere.diagnosis.domain.UniversityGroup;
import java.time.Instant;
import java.util.List;
import java.util.Set;
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
 * 진단 조회 3종({@link DiagnosisQueryService}) MongoDB 통합 테스트. 이력·최근·단건 상세가 어떤 문서를 보여 주고 어떤 문서를 감추는지를 실제
 * Mongo로 고정한다.
 *
 * <p>협력자가 저장소 하나라 {@code @MockitoBean}이 없다 — 조회 3종은 문항 카탈로그·답 적용기·번역기·추천 리더를 한 줄도 쓰지 않는다.
 *
 * <p>패키지가 {@code infrastructure}인 이유는 {@link DiagnosisMongoRepository}가 package-private이라 다른 패키지에서
 * 주입할 수 없기 때문이다.
 */
@DataMongoTest
@Testcontainers
@TestPropertySource(properties = "mongock.enabled=false")
@Import({DiagnosisQueryService.class, DiagnosisRepositoryImpl.class, SequenceGenerator.class})
class DiagnosisQueryServiceIntegrationTest {

  @Container @ServiceConnection static MongoDBContainer mongo = new MongoDBContainer("mongo:7.0");

  private static final long OWNER = 500L;
  private static final long STRANGER = 501L;

  @Autowired DiagnosisQueryService queryService;
  @Autowired DiagnosisRepository diagnosisRepository;
  @Autowired DiagnosisMongoRepository diagnosisMongoRepository;
  @Autowired MongoTemplate mongoTemplate;

  @BeforeEach
  void setUp() {
    diagnosisMongoRepository.deleteAll();
  }

  @Test
  @DisplayName("이력 정렬 방향(asc/desc)을 실제로 반영한다")
  void historySortDirection() {
    Instant older = Instant.parse("2026-01-01T00:00:00Z");
    Instant newer = Instant.parse("2026-02-01T00:00:00Z");
    saveCompletedAt(OWNER, older);
    saveCompletedAt(OWNER, newer);

    assertThat(queryService.getHistory(OWNER, 0, 20, "submittedAt,desc").content())
        .extracting(DiagnosisResponse::submittedAt)
        .containsExactly(newer, older);
    assertThat(queryService.getHistory(OWNER, 0, 20, "submittedAt,asc").content())
        .extracting(DiagnosisResponse::submittedAt)
        .containsExactly(older, newer);
  }

  @Test
  @DisplayName("이력·최근은 확정 진단만 본다 — 폐기 기록과 미확정 초안은 제외한다")
  void onlyCompletedIsVisible() {
    diagnosisRepository.save(Diagnosis.startInProgress(OWNER));
    diagnosisRepository.save(completed(OWNER).discard(Instant.now()));

    assertThat(queryService.getHistory(OWNER, 0, 20, null).content()).isEmpty();
    assertThat(queryService.getLatest(OWNER).completed()).isFalse();
  }

  @Test
  @DisplayName("게스트가 만든 진단은 이력·최근에 잡히지 않는다(사용자 id가 없어 질의에 걸리지 않는다)")
  void guestDiagnosisIsInvisibleToMemberQueries() {
    diagnosisRepository.save(
        Diagnosis.startInProgressForGuest("anonymous-guest-key").toBuilder()
            .region(Region.SEOUL)
            .purpose(Purpose.STUDY)
            .university(UniversityGroup.SNU_CAU_SOONGSIL)
            .conditions(Set.of(DiagnosisCondition.FEMALE_ONLY))
            .monthlyRentMin(200000)
            .monthlyRentMax(500000)
            .arcStatus(ArcStatus.ARC_ISSUED)
            .build()
            .complete(Instant.now()));

    assertThat(queryService.getHistory(OWNER, 0, 20, null).content()).isEmpty();
    assertThat(queryService.getLatest(OWNER).completed()).isFalse();
  }

  @Test
  @DisplayName("확정 진단이 없으면 최근 조회는 completed=false이고 요약 필드가 모두 null이다(404가 아니다)")
  void latestWhenNone() {
    var latest = queryService.getLatest(OWNER);
    assertThat(latest.completed()).isFalse();
    assertThat(latest.diagnosisId()).isNull();
    assertThat(latest.region()).isNull();
    assertThat(latest.submittedAt()).isNull();
  }

  @Test
  @DisplayName("상세는 폐기 기록을 404로 감춘다 — 본인 것이고 id를 알아도 노출 경로가 없다")
  void detailHidesDiscarded() {
    Long id = diagnosisRepository.save(completed(OWNER).discard(Instant.now())).getId();
    assertThatThrownBy(() -> queryService.getDetail(OWNER, id))
        .isInstanceOf(DiagnosisNotFoundException.class);
  }

  @Test
  @DisplayName("상세는 미확정 초안도 404다 — 조건이 비어 있어 진단 결과로 오인된다")
  void detailHidesInProgress() {
    Long id = diagnosisRepository.save(Diagnosis.startInProgress(OWNER)).getId();
    assertThatThrownBy(() -> queryService.getDetail(OWNER, id))
        .isInstanceOf(DiagnosisNotFoundException.class);
  }

  @Test
  @DisplayName("상태 검사가 소유권보다 먼저 돈다 — 타인의 미확정 진단은 403이 아니라 404다")
  void stateGateRunsBeforeOwnership() {
    Long id = diagnosisRepository.save(Diagnosis.startInProgress(OWNER)).getId();
    // 403이면 "그 id에 진단이 있다"가 새어 나간다. 진단 id는 전역 순차 채번이라 열거가 쉽다.
    assertThatThrownBy(() -> queryService.getDetail(STRANGER, id))
        .isInstanceOf(DiagnosisNotFoundException.class);
  }

  @Test
  @DisplayName("상세는 타인 소유 확정 진단에 403이다")
  void detailRejectsStranger() {
    Long id = diagnosisRepository.save(completed(OWNER).complete(Instant.now())).getId();
    assertThatThrownBy(() -> queryService.getDetail(STRANGER, id))
        .isInstanceOf(DiagnosisAccessDeniedException.class);
  }

  @Test
  @DisplayName("상세는 게스트 진단을 회원 토큰으로 열지 않는다(신원 종류가 다르면 거절)")
  void detailRejectsGuestDiagnosisForMember() {
    Long id =
        diagnosisRepository
            .save(
                Diagnosis.startInProgressForGuest("anonymous-guest-key").toBuilder()
                    .region(Region.SEOUL)
                    .purpose(Purpose.STUDY)
                    .university(UniversityGroup.SNU_CAU_SOONGSIL)
                    .conditions(Set.of(DiagnosisCondition.FEMALE_ONLY))
                    .monthlyRentMin(200000)
                    .monthlyRentMax(500000)
                    .arcStatus(ArcStatus.ARC_ISSUED)
                    .build()
                    .complete(Instant.now()))
            .getId();

    assertThatThrownBy(() -> queryService.getDetail(OWNER, id))
        .isInstanceOf(DiagnosisAccessDeniedException.class);
  }

  @Test
  @DisplayName("없는 진단은 404다")
  void detailNotFound() {
    assertThatThrownBy(() -> queryService.getDetail(OWNER, 9_999_999L))
        .isInstanceOf(DiagnosisNotFoundException.class);
  }

  @Test
  @DisplayName("페이지·정렬 파라미터 위반은 INVALID_INPUT이다")
  void pageAndSortValidation() {
    assertThatThrownBy(() -> queryService.getHistory(OWNER, -1, 20, null))
        .isInstanceOf(InvalidInputException.class);
    assertThatThrownBy(() -> queryService.getHistory(OWNER, 0, 101, null))
        .isInstanceOf(InvalidInputException.class);
    assertThatThrownBy(() -> queryService.getHistory(OWNER, 0, 20, "unknownKey,desc"))
        .isInstanceOf(InvalidInputException.class);
    assertThatThrownBy(() -> queryService.getHistory(OWNER, 0, 20, "submittedAt,sideways"))
        .isInstanceOf(InvalidInputException.class);
  }

  @Test
  @DisplayName("페이지 메타의 totalPages·hasNext가 전체 건수를 반영한다")
  void pageMeta() {
    for (int i = 0; i < 3; i++) {
      saveCompletedAt(OWNER, Instant.parse("2026-0" + (i + 1) + "-01T00:00:00Z"));
    }
    var page = queryService.getHistory(OWNER, 0, 2, null).page();
    assertThat(page.totalElements()).isEqualTo(3L);
    assertThat(page.totalPages()).isEqualTo(2);
    assertThat(page.hasNext()).isTrue();
  }

  @Test
  @DisplayName("도메인 enum에 없는 조건 문자열은 조용히 버려진다 — 조회가 깨지지는 않는다")
  void legacyUnknownConditionIsSilentlyDropped() {
    // 과거 마이그레이션이 conditions 에 NO_ARC 를 넣었다가 나중에 enum 상수만 되돌아갔다.
    // 그런 값을 만나면 변환이 실패해 조회가 깨질 것 같지만, 실제로는 드라이버가 그 원소만 빼고 읽는다.
    // 즉 미등록 값은 응답에서 사라질 뿐 500이 되지 않는다 — 이 성질을 여기서 못 박아 둔다.
    // 도메인 빌더로는 심을 수 없는 상태라 원시 문서를 직접 넣는다.
    mongoTemplate.getCollection("diagnoses").insertOne(rawDocumentWithUnknownCondition());

    var content = queryService.getHistory(OWNER, 0, 20, null).content();

    assertThat(content).hasSize(1);
    assertThat(content.get(0).conditions())
        .describedAs("미등록 값은 조용히 빠지고 나머지는 그대로 읽힌다")
        .containsExactly(DiagnosisCondition.PRIVATE_BATH);
  }

  private static Document rawDocumentWithUnknownCondition() {
    return new Document("_id", 9_000_001L)
        .append("userId", OWNER)
        .append("region", "SEOUL")
        .append("purpose", "STUDY")
        .append("university", "SNU_CAU_SOONGSIL")
        .append("conditions", List.of("PRIVATE_BATH", "NO_ARC"))
        .append("monthlyRentMin", 200000)
        .append("monthlyRentMax", 500000)
        .append("arcStatus", "ARC_ISSUED")
        .append("status", "COMPLETED")
        .append("submittedAt", java.util.Date.from(Instant.parse("2026-03-01T00:00:00Z")));
  }

  private void saveCompletedAt(long userId, Instant submittedAt) {
    diagnosisRepository.save(completed(userId).complete(submittedAt));
  }

  /** 확정 직전 상태의 완성된 초안. {@code complete}·{@code discard}로 종료 상태를 만든다. */
  private static Diagnosis completed(long userId) {
    return Diagnosis.startInProgress(userId).toBuilder()
        .region(Region.SEOUL)
        .purpose(Purpose.STUDY)
        .university(UniversityGroup.SNU_CAU_SOONGSIL)
        .conditions(Set.of(DiagnosisCondition.FEMALE_ONLY))
        .monthlyRentMin(200000)
        .monthlyRentMax(500000)
        .arcStatus(ArcStatus.ARC_ISSUED)
        .build();
  }
}
