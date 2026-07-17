package com.kohere.diagnosis.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.kohere.common.exception.InvalidInputException;
import com.kohere.common.response.PageInfo;
import com.kohere.common.response.PageResponse;
import com.kohere.diagnosis.application.DiagnosisAnswerApplier;
import com.kohere.diagnosis.application.DiagnosisCriteriaMapper;
import com.kohere.diagnosis.application.DiagnosisFlowService;
import com.kohere.diagnosis.application.DiagnosisQuestionTranslator;
import com.kohere.diagnosis.application.DiagnosisRecommendationReader;
import com.kohere.diagnosis.application.dto.DiagnosisFlowResponse;
import com.kohere.diagnosis.application.dto.FlowResultCode;
import com.kohere.diagnosis.application.dto.RecommendationResultCode;
import com.kohere.diagnosis.application.dto.V2RecommendationResponse;
import com.kohere.diagnosis.domain.DiagnosisAccessDeniedException;
import com.kohere.diagnosis.domain.DiagnosisFlowSessionNotFoundException;
import com.kohere.diagnosis.domain.DiagnosisNotFoundException;
import com.kohere.diagnosis.domain.DiagnosisStatus;
import com.kohere.diagnosis.domain.Region;
import com.kohere.diagnosis.infrastructure.DiagnosisQuestionDocument.OptionSpec;
import com.kohere.diagnosis.infrastructure.DiagnosisQuestionDocument.SelectSpec;
import com.kohere.diagnosis.presentation.dto.AnswerRequest;
import com.kohere.listing.api.ListingCodeLabelView;
import com.kohere.listing.api.ListingRecommendationService;
import com.kohere.listing.api.RecommendedListingView;
import com.kohere.user.api.UserAccountService;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * v2 서버 주도 진단 흐름({@link DiagnosisFlowService}) MongoDB 통합 테스트(issue #157·ADR-0036). 실제 Mongo로 클라 주도
 * 시작·진행 세션 영속·문항 순서·지역 조기 게이트·예외질문(일반 질문)·재시도/종료 코드·자동 확정·추천 조회를 end-to-end 검증한다.
 * cross-module(listing 추천·user 국가)은 mock으로 대체한다.
 *
 * <p>주도권 경계도 여기서 못 박는다 — 확정 시 서버가 추천을 미리 조회하지 않고(listing 호출은 ① 지역 게이트 1회뿐), 매칭 0건은 흐름 결과코드가 아니라 클라가
 * 요청한 추천 응답의 {@code resultCode=NO_MATCH}로 드러난다.
 */
@DataMongoTest
@Testcontainers
@TestPropertySource(properties = "mongock.enabled=false")
@Import({
  DiagnosisFlowService.class,
  DiagnosisAnswerApplier.class,
  DiagnosisCriteriaMapper.class,
  DiagnosisQuestionTranslator.class,
  DiagnosisRecommendationReader.class,
  DiagnosisFlowSessionRepositoryImpl.class,
  DiagnosisRepositoryImpl.class,
  SequenceGenerator.class,
  DiagnosisQuestionCatalogImpl.class
})
class DiagnosisFlowServiceIntegrationTest {

  @Container @ServiceConnection static MongoDBContainer mongo = new MongoDBContainer("mongo:7.0");

  @Autowired DiagnosisFlowService flowService;
  @Autowired DiagnosisMongoRepository diagnosisMongoRepository;
  @Autowired DiagnosisQuestionMongoRepository questionMongoRepository;
  @Autowired DiagnosisFlowSessionMongoRepository flowSessionMongoRepository;
  @Autowired MongoTemplate mongoTemplate;

  @MockitoBean ListingRecommendationService listingRecommendationService;
  @MockitoBean UserAccountService userAccountService;

  @BeforeEach
  void setUp() {
    diagnosisMongoRepository.deleteAll();
    questionMongoRepository.deleteAll();
    flowSessionMongoRepository.deleteAll();
    ensureUserIdUniqueIndex();
    seedCatalog();
    given(userAccountService.getLanguage(anyLong())).willReturn("en");
    given(listingRecommendationService.recommendByCriteria(any(), anyString()))
        .willAnswer(
            invocation ->
                listingRecommendationService.recommendByCriteria(invocation.getArgument(0)));
  }

  /**
   * 프로덕션의 "사용자당 1 세션" UNIQUE 인덱스를 테스트 Mongo에도 만든다. 인덱스 생성 러너는 {@code @Profile("!test")}라 이 슬라이스에 뜨지
   * 않는데, 그러면 세션 교체가 유니크 제약과 경합하는지를 테스트가 아예 볼 수 없다.
   */
  private void ensureUserIdUniqueIndex() {
    mongoTemplate
        .indexOps("diagnosisFlowSessions")
        .createIndex(
            new Index().on("userId", Sort.Direction.ASC).unique().named("userId_unique_idx"));
  }

  /** 프로덕션의 "문항 field 유일" UNIQUE 인덱스({@code DiagnosisQuestionIndexInitializer})를 테스트 Mongo에도 만든다. */
  private void ensureQuestionFieldUniqueIndex() {
    mongoTemplate
        .indexOps("diagnosisQuestions")
        .createIndex(
            new Index().on("field", Sort.Direction.ASC).unique().named("field_unique_idx"));
  }

  @Test
  @DisplayName("start는 ① 지역 질문을 반환한다(step 1의 regionRetry 문항과 헷갈리지 않는다)")
  void startReturnsRegionQuestion() {
    DiagnosisFlowResponse res = flowService.start(1L);
    assertThat(res.resultCode()).isEqualTo(FlowResultCode.NEXT_QUESTION);
    assertThat(res.question().step()).isEqualTo(1);
    assertThat(res.question().field()).isEqualTo("region");
  }

  @Test
  @DisplayName("진행 중 세션이 있어도 start는 기존 진단 정보를 버리고 처음부터 시작한다")
  void startDiscardsInProgressSession() {
    given(listingRecommendationService.recommendByCriteria(any())).willReturn(pageOf(view()));
    long userId = 2L;
    flowService.start(userId);
    answer(userId, "region", "SEOUL");
    answer(userId, "purpose", "STUDY"); // 진단하다 이탈(draft에 region·purpose 있음)

    DiagnosisFlowResponse res = flowService.start(userId); // 홈 갔다 다시 진단 시작

    assertThat(res.question().field()).isEqualTo("region");
    DiagnosisFlowSessionDocument session =
        flowSessionMongoRepository.findByUserId(userId).orElseThrow();
    assertThat(session.getPendingField()).isEqualTo("region"); // 처음부터
    assertThat(session.getDraft().getRegion()).isNull();
    assertThat(session.getDraft().getPurpose()).isNull();
    assertThat(flowSessionMongoRepository.count()).isEqualTo(1); // 사용자당 1건 유지
  }

  @Test
  @DisplayName("start를 연달아 호출해도 세션 1건을 유지한다(사용자당 1 세션 UNIQUE와 경합하지 않는다)")
  void repeatedStartKeepsSingleSession() {
    // 시작 버튼 더블탭·RESTART 후 재시작. 삭제 후 삽입이면 유니크 인덱스와 경합해 중복 키로 깨질 수 있다.
    long userId = 14L;
    flowService.start(userId);
    DiagnosisFlowResponse res = flowService.start(userId);

    assertThat(res.resultCode()).isEqualTo(FlowResultCode.NEXT_QUESTION);
    assertThat(res.question().field()).isEqualTo("region");
    assertThat(flowSessionMongoRepository.count()).isEqualTo(1);
    // upsert가 _id를 부여해 이후 답 적용(save)이 같은 세션을 갱신한다.
    assertThat(flowSessionMongoRepository.findByUserId(userId).orElseThrow().getId()).isNotNull();
  }

  @Test
  @DisplayName("DISCARDED 진단은 추천 조회로 노출되지 않는다(내부 분석 기록 — id를 알아도 404)")
  void discardedIsNotFetchableByRecommendations() {
    given(listingRecommendationService.recommendByCriteria(any())).willReturn(emptyPage());
    flowService.start(90L);
    answer(90L, "region", "BUSAN");
    answer(90L, "regionRetry", "NO"); // → DISCARDED 저장

    Long id = onlyDiagnosisOf(90L).getId();

    // id는 순차 발급이라 추측 가능하다 — 소유권만으로는 자기 폐기 기록을 막지 못한다.
    assertThatThrownBy(() -> flowService.getRecommendations(90L, id, 0, 20, null))
        .isInstanceOf(DiagnosisNotFoundException.class);
  }

  @Test
  @DisplayName("예외질문에 '아니오'(포기)면 그 시도를 DISCARDED 진단으로 남긴다(지역 수요 신호)")
  void regionRetryNoLeavesDiscardedDiagnosis() {
    given(listingRecommendationService.recommendByCriteria(any())).willReturn(emptyPage());
    flowService.start(20L);
    answer(20L, "region", "BUSAN");
    answer(20L, "regionRetry", "NO");

    DiagnosisDocument discarded = onlyDiagnosisOf(20L);
    assertThat(discarded.getStatus()).isEqualTo(DiagnosisStatus.DISCARDED);
    assertThat(discarded.getRegion()).isEqualTo(Region.BUSAN);
    assertThat(discarded.getPurpose()).isNull(); // 부분 답 그대로
    assertThat(discarded.getSubmittedAt()).isNotNull(); // 종료 시각
  }

  @Test
  @DisplayName("예외질문에 '예'(재시도)여도 그 시도는 DISCARDED로 남는다 — 안 남기면 원래 원한 지역이 증발한다")
  void regionRetryYesAlsoLeavesDiscardedDiagnosis() {
    given(listingRecommendationService.recommendByCriteria(any())).willReturn(emptyPage());
    flowService.start(21L);
    answer(21L, "region", "BUSAN");
    answer(21L, "regionRetry", "YES");

    DiagnosisDocument discarded = onlyDiagnosisOf(21L);
    assertThat(discarded.getStatus()).isEqualTo(DiagnosisStatus.DISCARDED);
    assertThat(discarded.getRegion()).isEqualTo(Region.BUSAN);
  }

  @Test
  @DisplayName("답하다 이탈한 세션은 start가 덮어쓸 때 기록하지 않고 버린다(지역 0건 경로만 남긴다)")
  void startDoesNotRecordAbandonedSession() {
    // 이탈은 요청으로 오지 않아 '돌아왔을 때'에야 알 수 있다 — 영영 안 돌아오면 집계에서 빠지고 시각도
    // 실제 이탈 시각이 아니라 재시작 시각이 된다. 부정확한 데이터라 남기지 않기로 했다(ADR-0036 결정 12).
    given(listingRecommendationService.recommendByCriteria(any())).willReturn(pageOf(view()));
    long userId = 22L;
    flowService.start(userId);
    answer(userId, "region", "SEOUL");
    answer(userId, "purpose", "STUDY"); // 여기까지 답하고 이탈

    flowService.start(userId); // 홈 갔다 다시 시작

    assertThat(diagnosisMongoRepository.findAll()).isEmpty();
  }

  @Test
  @DisplayName("DISCARDED 진단은 v1 진행 중 초안 조회에 잡히지 않는다(v1 흐름 오염 없음)")
  void discardedIsInvisibleToV1InProgressLookup() {
    given(listingRecommendationService.recommendByCriteria(any())).willReturn(emptyPage());
    flowService.start(24L);
    answer(24L, "region", "BUSAN");
    answer(24L, "regionRetry", "NO"); // → DISCARDED 저장

    assertThat(
            diagnosisMongoRepository.findFirstByUserIdAndStatus(24L, DiagnosisStatus.IN_PROGRESS))
        .isEmpty();
  }

  private DiagnosisDocument onlyDiagnosisOf(long userId) {
    return diagnosisMongoRepository.findAll().stream()
        .filter(d -> d.getUserId() == userId)
        .reduce(
            (a, b) -> {
              throw new AssertionError("진단이 2건 이상입니다(기록 중복)");
            })
        .orElseThrow(() -> new AssertionError("진단이 없습니다"));
  }

  @Test
  @DisplayName("세션 없이 next를 호출하면 DIAGNOSIS_SESSION_NOT_FOUND(서버가 임의로 흐름을 되살리지 않는다)")
  void nextWithoutSessionRejected() {
    assertThatThrownBy(() -> answer(3L, "region", "SEOUL"))
        .isInstanceOf(DiagnosisFlowSessionNotFoundException.class);
  }

  @Test
  @DisplayName("① 지역에 매물이 있으면 다음(② 목적) 질문으로 진행한다")
  void regionWithMatchProceedsToPurpose() {
    given(listingRecommendationService.recommendByCriteria(any())).willReturn(pageOf(view()));
    flowService.start(4L);
    DiagnosisFlowResponse res = answer(4L, "region", "SEOUL");
    assertThat(res.resultCode()).isEqualTo(FlowResultCode.NEXT_QUESTION);
    assertThat(res.question().field()).isEqualTo("purpose");
  }

  @Test
  @DisplayName("① 지역에 매물이 0건이면 카탈로그의 regionRetry 문항을 일반 질문(NEXT_QUESTION)으로 낸다")
  void regionNoMatchAsksRegionRetryAsNormalQuestion() {
    given(listingRecommendationService.recommendByCriteria(any())).willReturn(emptyPage());
    flowService.start(5L);
    DiagnosisFlowResponse res = answer(5L, "region", "BUSAN");

    assertThat(res.resultCode()).isEqualTo(FlowResultCode.NEXT_QUESTION);
    assertThat(res.question().field()).isEqualTo("regionRetry");
    assertThat(res.question().question()).isEqualTo("Would you like another region?");
    assertThat(res.question().options()).extracting(o -> o.code()).containsExactly("YES", "NO");
  }

  @Test
  @DisplayName("예외질문에 '예'면 재시도 코드(RESTART)만 반환하고 세션을 삭제한다(시작은 클라가 /start로)")
  void regionRetryYesReturnsRestartCode() {
    given(listingRecommendationService.recommendByCriteria(any())).willReturn(emptyPage());
    flowService.start(6L);
    answer(6L, "region", "BUSAN"); // → regionRetry 질문
    DiagnosisFlowResponse res = answer(6L, "regionRetry", "YES");

    assertThat(res.resultCode()).isEqualTo(FlowResultCode.RESTART);
    assertThat(res.question()).isNull();
    assertThat(flowSessionMongoRepository.findByUserId(6L)).isEmpty();
  }

  @Test
  @DisplayName("예외질문에 '아니오'면 진단종료 코드(TERMINATED)를 반환하고 세션을 삭제한다")
  void regionRetryNoTerminates() {
    given(listingRecommendationService.recommendByCriteria(any())).willReturn(emptyPage());
    flowService.start(7L);
    answer(7L, "region", "BUSAN");
    DiagnosisFlowResponse res = answer(7L, "regionRetry", "NO");

    assertThat(res.resultCode()).isEqualTo(FlowResultCode.TERMINATED);
    assertThat(res.question()).isNull();
    assertThat(flowSessionMongoRepository.findByUserId(7L)).isEmpty();
  }

  @Test
  @DisplayName("6단계를 마치면 자동 확정하고 COMPLETED(diagnosisId만 — 매물은 클라가 별도 조회)를 반환한다")
  void fullFlowAutoConfirmsCompleted() {
    given(listingRecommendationService.recommendByCriteria(any())).willReturn(pageOf(view()));
    long userId = 8L;
    DiagnosisFlowResponse res = runStudyFlow(userId);

    assertThat(res.resultCode()).isEqualTo(FlowResultCode.COMPLETED);
    assertThat(res.diagnosisId()).isNotNull();
    assertThat(res.question()).isNull();
    // 확정 진단은 v1과 동일한 diagnoses 컬렉션에 저장되고, 진행 세션은 삭제된다.
    assertThat(diagnosisMongoRepository.findById(res.diagnosisId())).isPresent();
    assertThat(flowSessionMongoRepository.findByUserId(userId)).isEmpty();
  }

  @Test
  @DisplayName("확정 시 서버는 추천을 조회하지 않는다 — listing 호출은 ① 지역 게이트 1회뿐(매물 요청은 클라가 결정)")
  void autoConfirmDoesNotQueryRecommendations() {
    given(listingRecommendationService.recommendByCriteria(any())).willReturn(pageOf(view()));
    DiagnosisFlowResponse res = runStudyFlow(9L);

    assertThat(res.resultCode()).isEqualTo(FlowResultCode.COMPLETED);
    // 6단계 흐름 전체에서 listing을 부른 건 ① 지역 조기 게이트 한 번뿐이다. 확정 시점에 매칭을 미리
    // 확인하면(NO_MATCH 판정용) 클라가 요청하지도 않은 추천 쿼리를 서버가 돌리는 셈이라 호출이 2회가 된다.
    verify(listingRecommendationService, times(1)).recommendByCriteria(any());
  }

  @Test
  @DisplayName("최종 매칭이 0건이어도 확정은 COMPLETED다(no-match는 클라가 추천을 조회할 때 드러난다)")
  void fullFlowWithNoFinalMatchStillCompletes() {
    // 지역 게이트만 통과시키고, 이후 최종 조건 매칭이 0건이어도 흐름 응답은 COMPLETED여야 한다.
    given(listingRecommendationService.recommendByCriteria(any())).willReturn(pageOf(view()));
    DiagnosisFlowResponse res = runStudyFlow(11L);

    assertThat(res.resultCode()).isEqualTo(FlowResultCode.COMPLETED);
    assertThat(res.diagnosisId()).isNotNull();
    assertThat(res.question()).isNull();
    assertThat(diagnosisMongoRepository.findById(res.diagnosisId())).isPresent();

    // 그 진단으로 추천을 조회하면 0건 — 사유는 resultCode로 오고 조정 제안(문구·액션)은 없다.
    given(listingRecommendationService.recommendByCriteria(any())).willReturn(emptyPage());
    V2RecommendationResponse rec =
        flowService.getRecommendations(11L, res.diagnosisId(), 0, 20, null);

    assertThat(rec.resultCode()).isEqualTo(RecommendationResultCode.NO_MATCH);
    assertThat(rec.content()).isEmpty();
    assertThat(rec.markers()).isEmpty();
  }

  @Test
  @DisplayName("확정 진단의 추천을 클라가 조회하면 MATCHED + 매물·마커를 반환한다(제안 필드 없음)")
  void getRecommendationsReturnsListings() {
    given(listingRecommendationService.recommendByCriteria(any())).willReturn(pageOf(view()));
    DiagnosisFlowResponse res = runStudyFlow(12L);

    V2RecommendationResponse rec =
        flowService.getRecommendations(12L, res.diagnosisId(), 0, 20, null);

    assertThat(rec.resultCode()).isEqualTo(RecommendationResultCode.MATCHED);
    assertThat(rec.content()).hasSize(1);
    assertThat(rec.markers()).hasSize(1);
    assertThat(rec.page().size()).isEqualTo(20);
  }

  @Test
  @DisplayName("타인 진단의 추천은 조회할 수 없다")
  void getRecommendationsOfOtherUserRejected() {
    given(listingRecommendationService.recommendByCriteria(any())).willReturn(pageOf(view()));
    DiagnosisFlowResponse res = runStudyFlow(13L);

    assertThatThrownBy(() -> flowService.getRecommendations(999L, res.diagnosisId(), 0, 20, null))
        .isInstanceOf(DiagnosisAccessDeniedException.class);
  }

  @Test
  @DisplayName("터미널(TERMINATED) 직후 재-POST는 세션이 없어 거부된다(중복 확정 방지)")
  void terminalRePostRejected() {
    given(listingRecommendationService.recommendByCriteria(any())).willReturn(emptyPage());
    flowService.start(10L);
    answer(10L, "region", "BUSAN");
    answer(10L, "regionRetry", "NO"); // → TERMINATED, 세션 삭제

    assertThatThrownBy(() -> answer(10L, "arcStatus", "ARC_ISSUED")) // 스테일 답
        .isInstanceOf(DiagnosisFlowSessionNotFoundException.class);
  }

  @Test
  @DisplayName("현재 단계와 다른 field를 보내면 INVALID_INPUT(순서 강제)")
  void wrongFieldRejected() {
    flowService.start(11L); // 기대 field=region
    assertThatThrownBy(() -> answer(11L, "purpose", "STUDY"))
        .isInstanceOf(InvalidInputException.class);
  }

  @Test
  @DisplayName("예외질문 대기 중 다음 슬롯(purpose) 답을 보내면 INVALID_INPUT — 기대 답은 순서가 아니라 낸 문항이 정한다")
  void pendingRegionRetryRejectsNextSlotAnswer() {
    // 지역을 답했으니 정본 순서상 다음은 purpose다. 기대 답을 그 순서로 역산하면 이 답이 통과해 버린다
    // — pendingField(=regionRetry)가 정본이어야 막힌다.
    given(listingRecommendationService.recommendByCriteria(any())).willReturn(emptyPage());
    flowService.start(15L);
    answer(15L, "region", "BUSAN"); // → regionRetry 문항(정본 슬롯은 전진하지 않음)

    assertThatThrownBy(() -> answer(15L, "purpose", "STUDY"))
        .isInstanceOf(InvalidInputException.class);

    // 세션은 살아 있고 여전히 regionRetry를 기다린다(잘못된 답이 흐름을 망가뜨리지 않는다).
    DiagnosisFlowResponse res = answer(15L, "regionRetry", "YES");
    assertThat(res.resultCode()).isEqualTo(FlowResultCode.RESTART);
  }

  @Test
  @DisplayName("문항 field가 중복되면 시드 시점에 막힌다(조회 키의 유일성)")
  void duplicateQuestionFieldRejected() {
    // 카탈로그가 순서를 담지 않고 field로 문항을 식별하므로, 중복이 생기면 어느 문항이 나갈지가 시드 순서에
    // 좌우된다. UNIQUE 인덱스가 그걸 조용히 넘기지 않고 시드 시점에 막아야 한다.
    ensureQuestionFieldUniqueIndex();

    assertThatThrownBy(() -> saveQuestion("region", "SINGLE", 1, "BUSAN", "Busan"))
        .isInstanceOf(DuplicateKeyException.class);
  }

  @Test
  @DisplayName("③ 분기(university/district)를 답해도 다음은 ④ 조건이다(두 field가 같은 슬롯)")
  void branchFieldsBothAdvanceToConditions() {
    // ofField("university")와 ofField("district")가 둘 다 UNIVERSITY_OR_DISTRICT여야 다음이 CONDITIONS가 된다.
    given(listingRecommendationService.recommendByCriteria(any())).willReturn(pageOf(view()));

    flowService.start(30L);
    answer(30L, "region", "SEOUL");
    answer(30L, "purpose", "STUDY");
    assertThat(answer(30L, "university", "SNU_CAU_SOONGSIL").question().field())
        .isEqualTo("conditions");

    flowService.start(31L);
    answer(31L, "region", "SEOUL");
    answer(31L, "purpose", "NON_STUDY");
    assertThat(answer(31L, "district", "GWANAK_GU").question().field()).isEqualTo("conditions");
  }

  @Test
  @DisplayName("세션은 서버가 직전에 낸 문항(pendingField)을 기록한다")
  void sessionRecordsPendingField() {
    given(listingRecommendationService.recommendByCriteria(any())).willReturn(pageOf(view()));
    long userId = 16L;
    flowService.start(userId);
    assertThat(pendingFieldOf(userId)).isEqualTo("region");

    answer(userId, "region", "SEOUL"); // → ② 목적 질문
    assertThat(pendingFieldOf(userId)).isEqualTo("purpose");

    answer(userId, "purpose", "STUDY"); // → ③ 분기(purpose=STUDY → university)
    assertThat(pendingFieldOf(userId)).isEqualTo("university");
  }

  private String pendingFieldOf(long userId) {
    return flowSessionMongoRepository.findByUserId(userId).orElseThrow().getPendingField();
  }

  @Test
  @DisplayName("next에 답(field)이 없으면 INVALID_INPUT(진행은 답으로만)")
  void nextWithoutAnswerRejected() {
    flowService.start(12L);
    assertThatThrownBy(() -> flowService.next(12L, null)).isInstanceOf(InvalidInputException.class);
  }

  @Test
  @DisplayName("예외질문 대기 중 YES/NO 외 응답은 INVALID_INPUT")
  void regionRetryInvalidCodeRejected() {
    given(listingRecommendationService.recommendByCriteria(any())).willReturn(emptyPage());
    flowService.start(13L);
    answer(13L, "region", "BUSAN");
    assertThatThrownBy(() -> answer(13L, "regionRetry", "MAYBE"))
        .isInstanceOf(InvalidInputException.class);
  }

  // --- helpers ---

  private DiagnosisFlowResponse answer(long userId, String field, String code) {
    return flowService.next(userId, new AnswerRequest(field, code, null, null, null));
  }

  /** ① 지역부터 ⑥ ARC까지 순서대로 답해 마지막 답(자동 확정)의 결과를 반환한다. */
  private DiagnosisFlowResponse runStudyFlow(long userId) {
    flowService.start(userId);
    answer(userId, "region", "SEOUL");
    answer(userId, "purpose", "STUDY");
    answer(userId, "university", "SNU_CAU_SOONGSIL");
    flowService.next(
        userId, new AnswerRequest("conditions", null, Set.of("FEMALE_ONLY"), null, null));
    flowService.next(userId, new AnswerRequest("monthlyRent", null, null, 200000, 500000));
    return answer(userId, "arcStatus", "ARC_ISSUED");
  }

  private static RecommendedListingView view() {
    return new RecommendedListingView(
        "6858e2000000000000000001",
        "Cozy",
        new ListingCodeLabelView("GOSHIWON", "Goshiwon"),
        300000,
        450000,
        500000,
        700000,
        "http://img",
        37.5,
        126.9,
        List.of(new ListingCodeLabelView("FEMALE_ONLY", "Female Only")));
  }

  private static PageResponse<RecommendedListingView> emptyPage() {
    return PageResponse.of(List.of(), new PageInfo(0, 20, 0L, 0, false));
  }

  private static PageResponse<RecommendedListingView> pageOf(RecommendedListingView... views) {
    List<RecommendedListingView> content = List.of(views);
    return PageResponse.of(content, new PageInfo(0, 20, content.size(), 1, false));
  }

  private void seedCatalog() {
    saveQuestion("region", "SINGLE", 1, "SEOUL", "Seoul");
    saveRegionRetry();
    saveQuestion("purpose", "SINGLE", 1, "STUDY", "Study");
    saveQuestion("university", "SINGLE", 1, "SNU_CAU_SOONGSIL", "Seoul National");
    saveQuestion("district", "SINGLE", 1, "GURO_GU", "Guro-gu");
    saveQuestion("conditions", "MULTI", 3, "FEMALE_ONLY", "Female only");
    saveNumberRange("monthlyRent");
    saveQuestion("arcStatus", "SINGLE", 1, "ARC_ISSUED", "ARC issued");
  }

  /** ① 지역 0건 예외질문 — region과 같은 step 1에 나란히 있는 카탈로그 문항(서버 합성 아님). */
  private void saveRegionRetry() {
    questionMongoRepository.save(
        DiagnosisQuestionDocument.builder()
            .field("regionRetry")
            .active(true)
            .question(Map.of("en", "Would you like another region?"))
            .select(SelectSpec.builder().type("SINGLE").max(1).build())
            .options(
                List.of(
                    OptionSpec.builder().code("YES").label(Map.of("en", "Yes")).build(),
                    OptionSpec.builder().code("NO").label(Map.of("en", "No")).build()))
            .build());
  }

  private void saveQuestion(
      String field, String selectType, int max, String optionCode, String optionLabel) {
    questionMongoRepository.save(
        DiagnosisQuestionDocument.builder()
            .field(field)
            .active(true)
            .question(Map.of("en", field + " question"))
            .select(SelectSpec.builder().type(selectType).max(max).build())
            .options(
                List.of(
                    OptionSpec.builder().code(optionCode).label(Map.of("en", optionLabel)).build()))
            .build());
  }

  private void saveNumberRange(String field) {
    questionMongoRepository.save(
        DiagnosisQuestionDocument.builder()
            .field(field)
            .active(true)
            .question(Map.of("en", field + " question"))
            .select(SelectSpec.builder().type("NUMBER_RANGE").max(0).build())
            .options(List.of())
            .build());
  }
}
