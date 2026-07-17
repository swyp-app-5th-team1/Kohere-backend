package com.kohere.diagnosis.application;

import com.kohere.common.exception.InvalidInputException;
import com.kohere.diagnosis.application.dto.DiagnosisFlowResponse;
import com.kohere.diagnosis.application.dto.QuestionResponse;
import com.kohere.diagnosis.application.dto.V2RecommendationResponse;
import com.kohere.diagnosis.domain.Diagnosis;
import com.kohere.diagnosis.domain.DiagnosisFlowSession;
import com.kohere.diagnosis.domain.DiagnosisFlowSessionNotFoundException;
import com.kohere.diagnosis.domain.DiagnosisFlowSessionRepository;
import com.kohere.diagnosis.domain.DiagnosisFlowStep;
import com.kohere.diagnosis.domain.DiagnosisQuestion;
import com.kohere.diagnosis.domain.DiagnosisQuestionCatalog;
import com.kohere.diagnosis.domain.DiagnosisRepository;
import com.kohere.diagnosis.presentation.dto.AnswerRequest;
import com.kohere.listing.api.ListingRecommendationService;
import com.kohere.listing.api.RecommendationCriteria;
import com.kohere.user.api.UserAccountService;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * v2 서버 주도 진단 흐름 엔진(issue #157·ADR-0036). 클라이언트가 {@code step}을 지정하지 않고 {@code POST
 * /api/v2/diagnoses/start}로 시작한 뒤 {@code POST /api/v2/diagnoses/next}를 반복 호출하면, 서버가 <b>직전에 낸
 * 문항</b>({@code pendingField})에서 다음 질문을 결정하고 마지막 슬롯을 답하면 자동 확정한다.
 *
 * <p><b>서버는 질문과 분기만 주도한다.</b> 진단 시작({@code /start})과 확정 매물 조회({@link #getRecommendations})는 클라이언트가
 * 결정한다 — 확정 응답에는 {@code diagnosisId}만 싣고 <b>매칭 유무조차 확인하지 않는다</b>. 0건인지는 클라가 추천을 요청한 그 응답의 {@code
 * resultCode=NO_MATCH}가 알려준다(no-match를 미리 계산해 내려보내려면 클라가 요청하지도 않은 추천 쿼리를 돌려야 한다).
 *
 * <p>① 지역 답 직후 매칭이 0건일 때만 서버가 예외적으로 미리 필터링한다 — {@link #regionHasMatch} 하나가 그 유일한 지점이다(0건인 지역으로 끝까지
 * 진행시키는 게 낭비라 명시적으로 둔 예외). 이때 끼워 넣는 "다른 지역?" 문항도 별도 결과코드가 아니라 카탈로그의 <b>일반 질문</b>({@code
 * field=regionRetry})으로 내려가며, 그 예/아니오 응답에만 클라이언트가 행할 행위를 코드로 알린다(예={@code RESTART} · 아니오={@code
 * TERMINATED}).
 *
 * <p>기존 v1({@link DiagnosisService})은 그대로 두고, 답 적용·조건 매핑·문항 번역·③ 분기는 공유 컴포넌트({@link
 * DiagnosisAnswerApplier}·{@link DiagnosisCriteriaMapper}·{@link DiagnosisQuestionTranslator})를
 * 재사용한다. 진행 상태는 v1의 {@code diagnoses}(IN_PROGRESS)와 분리된 {@link DiagnosisFlowSessionRepository}에 담고,
 * 완료 시에만 {@link DiagnosisRepository}로 정본 진단을 저장한다.
 *
 * <p>MongoDB 단일 노드 트랜잭션 미지원이라 {@code @Transactional}을 두지 않는다(연산은 단건 위주).
 */
@Service
@RequiredArgsConstructor
public class DiagnosisFlowService {

  /** ① 지역 조기 게이트의 존재 확인 조회 크기(매칭 유무만 필요 — 목록이 아니다). */
  private static final int REGION_GATE_SIZE = 1;

  /** ① 지역 0건 예외질문의 카탈로그 {@code field}. 정본 6슬롯 밖의 흐름 제어 문항이라 {@code DiagnosisFlowStep}에 없다. */
  private static final String REGION_RETRY_FIELD = "regionRetry";

  private static final String YES = "YES";
  private static final String NO = "NO";

  private final DiagnosisFlowSessionRepository flowSessionRepository;
  private final DiagnosisRepository diagnosisRepository;
  private final DiagnosisQuestionCatalog questionCatalog;
  private final ListingRecommendationService listingRecommendationService;
  private final UserAccountService userAccountService;
  private final DiagnosisAnswerApplier answerApplier;
  private final DiagnosisCriteriaMapper criteriaMapper;
  private final DiagnosisQuestionTranslator questionTranslator;
  private final DiagnosisRecommendationReader recommendationReader;

  /**
   * 진단을 처음부터 시작하고 ① 지역 질문을 반환한다. 진행 중 세션이 있어도 <b>버리고</b> 새로 만든다 — 진단을 중단했다 다시 시작하면 서버는 기존 진행 정보를 보지
   * 않는다(클라 주도 시작). 시작 버튼 더블탭에도 깨지지 않도록 삭제 후 삽입이 아니라 userId 기준 원자적 upsert로 교체한다.
   *
   * <p>덮어쓰는 이전 세션은 <b>기록하지 않고 버린다</b>. 이탈은 요청으로 오지 않아 돌아왔을 때에야 알 수 있는데, 그러면 영영 안 돌아온 사용자는 집계에서 빠지고
   * 시각도 실제 이탈 시각이 아니라 재시작 시각이라 신뢰할 수 없다. 폐기 기록({@code DISCARDED})은 그 자리에서 응답이 오는 ① 지역 0건 경로에만
   * 남긴다(ADR-0036 결정 12).
   */
  public DiagnosisFlowResponse start(long userId) {
    // 새 세션의 첫 문항은 언제나 ① 지역이고 DiagnosisFlowSession.start()가 그 field를 pendingField로
    // 담고 있으므로, 교체 한 번으로 끝낸다(ask로 다시 저장할 필요 없음).
    flowSessionRepository.upsertByUserId(DiagnosisFlowSession.start(userId));
    return DiagnosisFlowResponse.nextQuestion(
        question(userId, DiagnosisFlowStep.REGION.step(), DiagnosisFlowStep.REGION.field()));
  }

  /**
   * 현재 문항 답을 적용하고 다음 결과(다음 질문 / 자동 확정 결과 / 흐름 제어 코드)를 반환한다. 진행 중 세션이 없으면 {@code
   * DIAGNOSIS_SESSION_NOT_FOUND} — 서버가 임의로 흐름을 되살리지 않고 클라이언트가 {@code /start}로 다시 시작한다.
   */
  public DiagnosisFlowResponse next(long userId, AnswerRequest request) {
    DiagnosisFlowSession session =
        flowSessionRepository
            .findByUserId(userId)
            .orElseThrow(DiagnosisFlowSessionNotFoundException::new);
    if (request == null || request.field() == null || request.field().isBlank()) {
      throw new InvalidInputException("현재 문항의 답(field)이 필요합니다.");
    }
    // 기대 답은 서버가 직전에 낸 문항(pendingField)이다 — 정본 순서로 역산하지 않으므로 6슬롯에 없는
    // 예외질문(regionRetry)도 일반 문항과 같은 규칙으로 검증된다.
    if (!request.field().equals(session.getPendingField())) {
      throw new InvalidInputException("현재 문항의 답이 아닙니다: " + request.field());
    }
    if (REGION_RETRY_FIELD.equals(session.getPendingField())) {
      return handleRegionRetry(session, request);
    }

    Diagnosis updatedDraft = answerApplier.apply(session.getDraft(), request);
    DiagnosisFlowSession advanced = session.withDraft(updatedDraft);

    // 방금 ① 지역을 답했을 때만 지역 조기 게이트를 돈다 — 서버가 미리 필터링하는 유일한 지점.
    if (DiagnosisFlowStep.REGION.field().equals(request.field()) && !regionHasMatch(updatedDraft)) {
      return ask(advanced, DiagnosisFlowStep.REGION.step(), REGION_RETRY_FIELD);
    }
    return computeNext(advanced, request.field());
  }

  /**
   * 확정 진단의 추천 매물을 조회한다({@code GET /api/v2/diagnoses/{id}/recommendations}). <b>이 호출이 곧 "매물을 받겠다"는
   * 클라이언트의 결정</b>이며, 시점·페이지·정렬을 클라가 정한다.
   *
   * <p>v1 §7과 같은 조회지만 0건일 때 조정 제안 문구·액션({@code suggestions})을 붙이지 않는다 — v2는 제안 기능을 쓰지 않으므로 사유만
   * {@code resultCode}({@code MATCHED}/{@code NO_MATCH})로 알린다. 검증·소유권·조건 매핑은 v1과 공유한다({@link
   * DiagnosisRecommendationReader}).
   */
  public V2RecommendationResponse getRecommendations(
      long userId, Long diagnosisId, int page, int size, String sort) {
    return V2RecommendationResponse.from(
        recommendationReader.read(userId, diagnosisId, page, size, sort));
  }

  // --- ① 지역 0건 예외질문 응답 처리(예=재시도 / 아니오=종료) ---

  /**
   * 예/아니오를 클라이언트가 행할 행위 코드로 옮긴다. 둘 다 터미널이라 세션을 삭제하고, 재시도는 클라가 {@code /start}로 건다.
   *
   * <p>{@code field} 일치는 호출 전에 {@code pendingField}로 이미 검증됐다. 이 답은 진단 답이 아니라 흐름 제어 응답이라 {@code
   * draft}에 저장하지 않고 정본 슬롯도 전진시키지 않는다 — 그 점만 일반 문항과 다르다.
   */
  private DiagnosisFlowResponse handleRegionRetry(
      DiagnosisFlowSession session, AnswerRequest request) {
    String code = request.code();
    if (!YES.equals(code) && !NO.equals(code)) {
      throw new InvalidInputException("regionRetry 응답은 YES 또는 NO여야 합니다: " + code);
    }
    // 예("다른 지역")든 아니오(종료)든 이 시도는 여기서 끝난다 — 어느 쪽이든 "이 지역을 원했는데 매물이
    // 없었다"는 수요 신호라 폐기 기록으로 남긴다(사용자 비노출). 재시도를 안 남기면 사용자가 다른 지역으로
    // 완주했을 때 원래 원했던 지역이 증발한다. 이 상태는 ① 지역을 답한 뒤에만 도달하므로 draft에 region이
    // 있는 게 보장된다.
    diagnosisRepository.save(session.getDraft().discard(Instant.now()));
    flowSessionRepository.deleteByUserId(session.getUserId());
    return YES.equals(code) ? DiagnosisFlowResponse.restart() : DiagnosisFlowResponse.terminated();
  }

  // --- 다음 상태 계산(다음 질문 vs 자동 확정) ---

  /**
   * 방금 답한 문항의 <b>다음 슬롯</b> 문항을 내고, 마지막 슬롯이었으면 자동 확정한다.
   *
   * <p>진행 슬롯 수를 따로 세지 않고 정본 순서를 그대로 따라간다 — 순서는 {@link DiagnosisFlowStep}의 선언 순서가 정본이다.
   */
  private DiagnosisFlowResponse computeNext(DiagnosisFlowSession session, String answeredField) {
    DiagnosisFlowStep next = DiagnosisFlowStep.ofField(answeredField).next();
    if (next == null) {
      return autoConfirm(session); // 마지막 슬롯(⑥ ARC)을 답함 → 빌더 완성
    }
    return ask(session, next.step(), slotField(next, session.getDraft()));
  }

  /**
   * 문항 하나를 내고, 그 {@code field}를 세션에 기대 답으로 기록한다(정본 슬롯 문항·예외질문 공통 경로). 세션 저장이 여기 한 곳에 모여 "낸 문항"과 "기대
   * 답"이 어긋날 수 없다.
   */
  private DiagnosisFlowResponse ask(DiagnosisFlowSession session, int step, String field) {
    flowSessionRepository.save(session.awaiting(field));
    return DiagnosisFlowResponse.nextQuestion(question(session.getUserId(), step, field));
  }

  /**
   * 빌더 완성 → 서버가 자동 확정하고 {@code diagnosisId}만 반환한다(세션 삭제).
   *
   * <p><b>여기서 매칭을 확인하지 않는다.</b> 0건 여부를 알려면 추천 쿼리를 돌려야 하는데, 그건 클라이언트가 요청한 적 없는 조회다 — 매물을 언제 받을지는 클라가
   * 정하고({@link #getRecommendations}), 0건인지는 그 응답의 {@code resultCode=NO_MATCH}가 알려준다.
   */
  private DiagnosisFlowResponse autoConfirm(DiagnosisFlowSession session) {
    Diagnosis completed = diagnosisRepository.save(session.getDraft().complete(Instant.now()));
    flowSessionRepository.deleteByUserId(session.getUserId());
    return DiagnosisFlowResponse.completed(completed.getId());
  }

  // --- 헬퍼 ---

  /** 정본 슬롯이 낼 문항의 field(UNIVERSITY_OR_DISTRICT는 저장된 purpose로 university/district 택일). */
  private String slotField(DiagnosisFlowStep slot, Diagnosis draft) {
    if (slot == DiagnosisFlowStep.UNIVERSITY_OR_DISTRICT) {
      return questionTranslator.resolveBranchField(draft.getPurpose());
    }
    return slot.field();
  }

  /** 카탈로그에서 {@code field} 문항 1건을 꺼내 사용자 언어로 번역한다({@code step}은 코드가 정본이라 함께 넘긴다). */
  private QuestionResponse question(long userId, int step, String field) {
    DiagnosisQuestion question =
        questionCatalog
            .findByField(field)
            .orElseThrow(() -> new IllegalStateException("진단 문항 카탈로그가 없습니다: field=" + field));
    return questionTranslator.translate(question, step, resolveLanguage(userId));
  }

  /**
   * ① 지역만 채운 경량 조건으로 매칭이 하나라도 있는지 확인한다(size=1). 서버가 미리 필터링하는 <b>유일한</b> 지점이라 이름으로 못 박는다 — 다른 단계에서
   * 이걸 부르면 클라이언트가 요청하지 않은 추천 쿼리를 돌리는 셈이 된다.
   */
  private boolean regionHasMatch(Diagnosis draft) {
    RecommendationCriteria criteria = criteriaMapper.toCriteria(draft, 0, REGION_GATE_SIZE, null);
    return !listingRecommendationService.recommendByCriteria(criteria).content().isEmpty();
  }

  private String resolveLanguage(long userId) {
    return userAccountService.getLanguage(userId);
  }
}
