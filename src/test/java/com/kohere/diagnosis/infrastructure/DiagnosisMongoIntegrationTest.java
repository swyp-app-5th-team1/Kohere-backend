package com.kohere.diagnosis.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

import com.kohere.common.exception.InvalidInputException;
import com.kohere.common.response.PageInfo;
import com.kohere.common.response.PageResponse;
import com.kohere.diagnosis.application.DiagnosisService;
import com.kohere.diagnosis.application.SuggestionMessages;
import com.kohere.diagnosis.application.dto.DiagnosisCreatedResponse;
import com.kohere.diagnosis.application.dto.DiagnosisResponse;
import com.kohere.diagnosis.application.dto.LatestDiagnosisResponse;
import com.kohere.diagnosis.application.dto.QuestionResponse;
import com.kohere.diagnosis.application.dto.RecommendationResponse;
import com.kohere.diagnosis.domain.ArcStatus;
import com.kohere.diagnosis.domain.Diagnosis;
import com.kohere.diagnosis.domain.DiagnosisAccessDeniedException;
import com.kohere.diagnosis.domain.DiagnosisCondition;
import com.kohere.diagnosis.domain.DiagnosisNotFoundException;
import com.kohere.diagnosis.domain.DiagnosisRepository;
import com.kohere.diagnosis.domain.DiagnosisStatus;
import com.kohere.diagnosis.domain.Purpose;
import com.kohere.diagnosis.domain.Region;
import com.kohere.diagnosis.domain.University;
import com.kohere.diagnosis.infrastructure.DiagnosisQuestionDocument.OptionSpec;
import com.kohere.diagnosis.infrastructure.DiagnosisQuestionDocument.SelectSpec;
import com.kohere.diagnosis.presentation.dto.AnswerRequest;
import com.kohere.listing.api.ListingRecommendationService;
import com.kohere.listing.api.RecommendedListingView;
import com.kohere.user.api.UserAccountService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 진단 모듈 MongoDB 통합 테스트. 실제 Mongo 컨테이너로 영속·시퀀스 id·정렬 방향·문항 번역·추천 매핑·검증을 end-to-end 검증한다.
 * cross-module 협력(listing 추천·user 국가)은 mock으로 대체해 diagnosis 동작에 집중한다(test-strategy §4).
 */
@DataMongoTest
@Testcontainers
@Import({
  DiagnosisService.class,
  DiagnosisRepositoryImpl.class,
  SequenceGenerator.class,
  DiagnosisQuestionCatalogImpl.class,
  SuggestionCatalogImpl.class,
  SuggestionMessages.class
})
class DiagnosisMongoIntegrationTest {

  @Container @ServiceConnection static MongoDBContainer mongo = new MongoDBContainer("mongo:7.0");

  @Autowired DiagnosisService diagnosisService;
  @Autowired DiagnosisRepository diagnosisRepository;
  @Autowired DiagnosisMongoRepository diagnosisMongoRepository;
  @Autowired DiagnosisQuestionMongoRepository questionMongoRepository;
  @Autowired DiagnosisSuggestionMongoRepository suggestionMongoRepository;

  @MockitoBean ListingRecommendationService listingRecommendationService;
  @MockitoBean UserAccountService userAccountService;

  @BeforeEach
  void clean() {
    diagnosisMongoRepository.deleteAll();
    questionMongoRepository.deleteAll();
    suggestionMongoRepository.deleteAll();
  }

  @Test
  @DisplayName("단계별 답 저장 → 확정 → 상세·최근·이력 조회 end-to-end")
  void fullFlow() {
    long userId = 1L;
    answer(userId, "region", "SEOUL");
    answer(userId, "purpose", "STUDY");
    answer(userId, "university", "SNU");
    answerCodes(userId, "conditions", Set.of("FEMALE_ONLY", "PRIVATE_BATH"));
    answer(userId, "monthlyBudgetMax", "600000");
    answer(userId, "arcStatus", "ARC_ISSUED");

    DiagnosisCreatedResponse created = diagnosisService.submit(userId);
    assertThat(created.diagnosisId()).isNotNull();
    assertThat(created.status()).isEqualTo(DiagnosisStatus.COMPLETED);
    assertThat(created.submittedAt()).isNotNull();

    DiagnosisResponse detail = diagnosisService.getDetail(userId, created.diagnosisId());
    assertThat(detail.region()).isEqualTo(Region.SEOUL);
    assertThat(detail.purpose()).isEqualTo(Purpose.STUDY);
    assertThat(detail.university()).isEqualTo(University.SNU);
    assertThat(detail.district()).isNull();
    assertThat(detail.conditions())
        .containsExactlyInAnyOrder(DiagnosisCondition.FEMALE_ONLY, DiagnosisCondition.PRIVATE_BATH);
    assertThat(detail.monthlyBudgetMax()).isEqualTo(600000);
    assertThat(detail.arcStatus()).isEqualTo(ArcStatus.ARC_ISSUED);
    assertThat(detail.status()).isEqualTo(DiagnosisStatus.COMPLETED);

    LatestDiagnosisResponse latest = diagnosisService.getLatest(userId);
    assertThat(latest.completed()).isTrue();
    assertThat(latest.diagnosisId()).isEqualTo(created.diagnosisId());

    PageResponse<DiagnosisResponse> history =
        diagnosisService.getHistory(userId, 0, 20, "submittedAt,desc");
    assertThat(history.content()).hasSize(1);
    assertThat(history.page().totalElements()).isEqualTo(1L);
  }

  @Test
  @DisplayName("시퀀스 id는 순차 발급되고 재진단은 새 레코드를 만든다(기존 보존)")
  void sequentialIdAndRediagnosis() {
    long userId = 2L;
    completeStudyFlow(userId);
    DiagnosisCreatedResponse first = diagnosisService.submit(userId);

    completeStudyFlow(userId);
    DiagnosisCreatedResponse second = diagnosisService.submit(userId);

    assertThat(second.diagnosisId()).isGreaterThan(first.diagnosisId());
    assertThat(diagnosisService.getHistory(userId, 0, 20, "submittedAt,desc").content()).hasSize(2);
    assertThat(diagnosisService.getDetail(userId, first.diagnosisId())).isNotNull();
  }

  @Test
  @DisplayName("최근 진단 이력이 없으면 completed=false")
  void latestWhenNone() {
    assertThat(diagnosisService.getLatest(999L).completed()).isFalse();
  }

  @Test
  @DisplayName("진행 중 진단 없이 제출하면 INVALID_INPUT")
  void submitWithoutDraft() {
    assertThatThrownBy(() -> diagnosisService.submit(123L))
        .isInstanceOf(InvalidInputException.class);
  }

  @Test
  @DisplayName("필수 단계 미완료로 제출하면 INVALID_INPUT")
  void submitIncomplete() {
    long userId = 3L;
    answer(userId, "region", "SEOUL");
    assertThatThrownBy(() -> diagnosisService.submit(userId))
        .isInstanceOf(InvalidInputException.class);
  }

  @Test
  @DisplayName("문항은 등록 국가 언어로 번역되고, 미지원 언어는 영어로 폴백한다")
  void questionTranslation() {
    seedRegionQuestion();

    given(userAccountService.getLanguage(10L)).willReturn("ko");
    QuestionResponse ko = diagnosisService.getQuestion(10L, 1);
    assertThat(ko.field()).isEqualTo("region");
    assertThat(ko.question()).isEqualTo("지역 선택");
    assertThat(ko.options()).extracting(QuestionResponse.Option::label).contains("서울");

    given(userAccountService.getLanguage(11L)).willReturn("ja");
    QuestionResponse fallback = diagnosisService.getQuestion(11L, 1);
    assertThat(fallback.question()).isEqualTo("Select region");
    assertThat(fallback.options()).extracting(QuestionResponse.Option::label).contains("Seoul");
  }

  @Test
  @DisplayName("step 3은 저장된 purpose로 university/district를 택일한다(서버 분기)")
  void step3PurposeBranch() {
    seedUniversityQuestion();
    seedDistrictQuestion();
    given(userAccountService.getLanguage(anyLong())).willReturn("ko");

    long study = 12L;
    answer(study, "purpose", "STUDY");
    assertThat(diagnosisService.getQuestion(study, 3).field()).isEqualTo("university");

    long nonStudy = 13L;
    answer(nonStudy, "purpose", "NON_STUDY");
    assertThat(diagnosisService.getQuestion(nonStudy, 3).field()).isEqualTo("district");
  }

  @Test
  @DisplayName("purpose 미선행 step 3 / 범위 밖 step은 INVALID_INPUT")
  void questionInvalid() {
    assertThatThrownBy(() -> diagnosisService.getQuestion(14L, 3))
        .isInstanceOf(InvalidInputException.class);
    assertThatThrownBy(() -> diagnosisService.getQuestion(14L, 0))
        .isInstanceOf(InvalidInputException.class);
    assertThatThrownBy(() -> diagnosisService.getQuestion(14L, 7))
        .isInstanceOf(InvalidInputException.class);
  }

  @Test
  @DisplayName("잘못된 enum·조건 4개 초과·목적 불일치 답은 INVALID_INPUT")
  void answerValidation() {
    assertThatThrownBy(() -> answer(15L, "region", "MARS"))
        .isInstanceOf(InvalidInputException.class);
    assertThatThrownBy(
            () ->
                answerCodes(
                    16L,
                    "conditions",
                    Set.of("FEMALE_ONLY", "PRIVATE_BATH", "PRIVATE_TOILET", "MEALS_PROVIDED")))
        .isInstanceOf(InvalidInputException.class);

    long userId = 17L;
    answer(userId, "purpose", "NON_STUDY");
    assertThatThrownBy(() -> answer(userId, "university", "SNU"))
        .isInstanceOf(InvalidInputException.class);
  }

  @Test
  @DisplayName("단건/추천은 본인 소유만 — 타인 403, 미존재 404")
  void ownership() {
    long owner = 18L;
    completeStudyFlow(owner);
    DiagnosisCreatedResponse created = diagnosisService.submit(owner);

    assertThatThrownBy(() -> diagnosisService.getDetail(19L, created.diagnosisId()))
        .isInstanceOf(DiagnosisAccessDeniedException.class);
    assertThatThrownBy(() -> diagnosisService.getDetail(owner, 9_999_999L))
        .isInstanceOf(DiagnosisNotFoundException.class);
  }

  @Test
  @DisplayName("추천 0건이면 빈 목록 + 번역된 조정 제안(NO_MATCH)")
  void recommendationsNoMatch() {
    long userId = 20L;
    completeStudyFlow(userId);
    DiagnosisCreatedResponse created = diagnosisService.submit(userId);
    seedNoMatchSuggestion();
    given(userAccountService.getLanguage(anyLong())).willReturn("ko");
    given(listingRecommendationService.recommendByCriteria(any())).willReturn(emptyPage());

    RecommendationResponse rec =
        diagnosisService.getRecommendations(
            userId, created.diagnosisId(), 0, 20, "recommended,desc");
    assertThat(rec.content()).isEmpty();
    assertThat(rec.markers()).isEmpty();
    assertThat(rec.suggestions()).isNotNull();
    assertThat(rec.suggestions().reason()).isEqualTo("NO_MATCH");
    assertThat(rec.suggestions().message()).isEqualTo("조건에 맞는 매물이 없습니다. 아래 항목을 조정해 보세요.");
    assertThat(rec.suggestions().actions()).isNotEmpty();
  }

  @Test
  @DisplayName("추천 매물이 있으면 content로 매핑하고 마커를 좌표에서 파생한다")
  void recommendationsWithContent() {
    long userId = 21L;
    completeStudyFlow(userId);
    DiagnosisCreatedResponse created = diagnosisService.submit(userId);
    given(listingRecommendationService.recommendByCriteria(any()))
        .willReturn(
            pageOf(
                new RecommendedListingView(
                    5001L,
                    "Cozy",
                    "CO_LIVING",
                    550000,
                    1_000_000,
                    "http://img",
                    37.5,
                    126.9,
                    List.of("FEMALE_ONLY"))));

    RecommendationResponse rec =
        diagnosisService.getRecommendations(
            userId, created.diagnosisId(), 0, 20, "recommended,desc");
    assertThat(rec.content()).hasSize(1);
    assertThat(rec.content().get(0).listingId()).isEqualTo(5001L);
    assertThat(rec.content().get(0).type()).isEqualTo("CO_LIVING");
    assertThat(rec.markers()).hasSize(1);
    assertThat(rec.markers().get(0).lat()).isEqualTo(37.5);
    assertThat(rec.markers().get(0).lng()).isEqualTo(126.9);
    assertThat(rec.suggestions()).isNull();
  }

  @Test
  @DisplayName("이력 정렬 방향(asc/desc)을 실제로 반영한다")
  void historySortDirection() {
    long userId = 22L;
    Instant older = Instant.parse("2026-01-01T00:00:00Z");
    Instant newer = Instant.parse("2026-02-01T00:00:00Z");
    diagnosisRepository.save(completedAt(userId, older));
    diagnosisRepository.save(completedAt(userId, newer));

    List<DiagnosisResponse> desc =
        diagnosisService.getHistory(userId, 0, 20, "submittedAt,desc").content();
    assertThat(desc).extracting(DiagnosisResponse::submittedAt).containsExactly(newer, older);

    List<DiagnosisResponse> asc =
        diagnosisService.getHistory(userId, 0, 20, "submittedAt,asc").content();
    assertThat(asc).extracting(DiagnosisResponse::submittedAt).containsExactly(older, newer);
  }

  @Test
  @DisplayName("이력·최근 조회는 진행 중(IN_PROGRESS) 초안을 제외한다")
  void inProgressExcludedFromHistory() {
    long userId = 23L;
    diagnosisRepository.save(Diagnosis.startInProgress(userId));
    assertThat(diagnosisService.getHistory(userId, 0, 20, "submittedAt,desc").content()).isEmpty();
    assertThat(diagnosisService.getLatest(userId).completed()).isFalse();
  }

  // --- helpers ---

  private void answer(long userId, String field, String code) {
    diagnosisService.submitAnswer(userId, new AnswerRequest(field, code, null));
  }

  private void answerCodes(long userId, String field, Set<String> codes) {
    diagnosisService.submitAnswer(userId, new AnswerRequest(field, null, codes));
  }

  private void completeStudyFlow(long userId) {
    answer(userId, "region", "SEOUL");
    answer(userId, "purpose", "STUDY");
    answer(userId, "university", "SNU");
    answerCodes(userId, "conditions", Set.of("FEMALE_ONLY"));
    answer(userId, "monthlyBudgetMax", "500000");
    answer(userId, "arcStatus", "ARC_ISSUED");
  }

  private static Diagnosis completedAt(long userId, Instant submittedAt) {
    return Diagnosis.builder()
        .userId(userId)
        .region(Region.SEOUL)
        .purpose(Purpose.STUDY)
        .university(University.SNU)
        .conditions(Set.of())
        .monthlyBudgetMax(500000)
        .arcStatus(ArcStatus.ARC_ISSUED)
        .status(DiagnosisStatus.COMPLETED)
        .submittedAt(submittedAt)
        .build();
  }

  private static PageResponse<RecommendedListingView> emptyPage() {
    return PageResponse.of(List.of(), new PageInfo(0, 20, 0L, 0, false));
  }

  private static PageResponse<RecommendedListingView> pageOf(RecommendedListingView... views) {
    List<RecommendedListingView> content = List.of(views);
    return PageResponse.of(content, new PageInfo(0, 20, content.size(), 1, false));
  }

  private void seedRegionQuestion() {
    questionMongoRepository.save(
        DiagnosisQuestionDocument.builder()
            .step(1)
            .field("region")
            .active(true)
            .question(Map.of("en", "Select region", "ko", "지역 선택"))
            .select(SelectSpec.builder().type("SINGLE").max(1).build())
            .options(
                List.of(
                    OptionSpec.builder()
                        .code("SEOUL")
                        .label(Map.of("en", "Seoul", "ko", "서울"))
                        .build()))
            .build());
  }

  private void seedUniversityQuestion() {
    questionMongoRepository.save(
        DiagnosisQuestionDocument.builder()
            .step(3)
            .field("university")
            .active(true)
            .question(Map.of("en", "Which university?", "ko", "어느 대학교?"))
            .select(SelectSpec.builder().type("SINGLE").max(1).build())
            .options(
                List.of(
                    OptionSpec.builder()
                        .code("SNU")
                        .label(Map.of("en", "Seoul National University", "ko", "서울대학교"))
                        .build()))
            .build());
  }

  private void seedDistrictQuestion() {
    questionMongoRepository.save(
        DiagnosisQuestionDocument.builder()
            .step(3)
            .field("district")
            .active(true)
            .question(Map.of("en", "Which district?", "ko", "어느 구?"))
            .select(SelectSpec.builder().type("SINGLE").max(1).build())
            .options(
                List.of(
                    OptionSpec.builder()
                        .code("GURO_GU")
                        .label(Map.of("en", "Guro-gu", "ko", "구로구"))
                        .build()))
            .build());
  }

  private void seedNoMatchSuggestion() {
    suggestionMongoRepository.save(
        DiagnosisSuggestionDocument.builder()
            .id("NO_MATCH")
            .message(
                Map.of(
                    "en", "No listings matched your criteria. Try adjusting the options below.",
                    "ko", "조건에 맞는 매물이 없습니다. 아래 항목을 조정해 보세요."))
            .actions(
                List.of(
                    DiagnosisSuggestionDocument.ActionSpec.builder()
                        .type("RELAX_REGION")
                        .detail(Map.of("en", "Widen the region.", "ko", "지역 범위를 넓혀 보세요."))
                        .build()))
            .build());
  }
}
