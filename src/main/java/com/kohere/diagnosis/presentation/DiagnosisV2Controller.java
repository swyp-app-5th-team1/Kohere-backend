package com.kohere.diagnosis.presentation;

import com.kohere.common.response.ApiResponse;
import com.kohere.common.security.AuthPrincipal;
import com.kohere.diagnosis.application.DiagnosisFlowService;
import com.kohere.diagnosis.application.dto.DiagnosisFlowResponse;
import com.kohere.diagnosis.application.dto.V2RecommendationResponse;
import com.kohere.diagnosis.presentation.dto.AnswerRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * v2 서버 주도 진단 REST 컨트롤러(issue #157·ADR-0036). 클라이언트가 {@code POST /start}로 진단을 시작하고 {@code POST
 * /next}로 답을 이어 보내면, 서버가 {@code step} 없이 다음 질문·분기·확정 시점을 판단한다. v1({@code /api/v1/diagnoses/*})은 그대로
 * 유지된다.
 *
 * <p>시작은 클라이언트가 주도한다 — {@code /start}는 진행 중 세션이 있어도 버리고 처음부터 시작하며, 세션 없이 온 {@code /next}는 400으로
 * 막는다. 확정 매물 조회도 클라이언트가 {@code diagnosisId}로 v1 추천 엔드포인트를 호출해 결정한다.
 *
 * <p>입력 바인딩·응답 래핑만 담당하고 로직은 {@link DiagnosisFlowService}에 위임한다. 인증 필수이며 주체(userId)는
 * {@code @AuthenticationPrincipal AuthPrincipal}에서 꺼낸다(ADR-0010).
 *
 * <p>스펙: docs/api/specs/02-diagnosis-recommendation.md (v2) · 시퀀스 US-2-7.
 */
@RestController
@RequestMapping("/api/v2/diagnoses")
@RequiredArgsConstructor
public class DiagnosisV2Controller {

  private final DiagnosisFlowService diagnosisFlowService;

  /** 진단을 처음부터 시작하고 ① 지역 질문을 반환한다(진행 중 세션이 있으면 버린다). */
  @PostMapping("/start")
  public ApiResponse<DiagnosisFlowResponse> start(
      @AuthenticationPrincipal AuthPrincipal principal) {
    return ApiResponse.success(diagnosisFlowService.start(principal.userId()));
  }

  /** 현재 문항 답을 적용하고 다음 결과를 결과코드로 반환한다. */
  @PostMapping("/next")
  public ApiResponse<DiagnosisFlowResponse> next(
      @AuthenticationPrincipal AuthPrincipal principal,
      @RequestBody(required = false) AnswerRequest request) {
    return ApiResponse.success(diagnosisFlowService.next(principal.userId(), request));
  }

  /**
   * 확정 진단의 추천 매물·지도 좌표를 조회한다. 이 호출 자체가 "매물을 받겠다"는 클라이언트의 결정이며, 0건이면 빈 {@code content}가 곧
   * no-match다(조정 제안 없음 — v1 §7과 다른 점은 그것 하나).
   */
  @GetMapping("/{diagnosisId}/recommendations")
  public ApiResponse<V2RecommendationResponse> recommendations(
      @AuthenticationPrincipal AuthPrincipal principal,
      @PathVariable Long diagnosisId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String sort) {
    return ApiResponse.success(
        diagnosisFlowService.getRecommendations(principal.userId(), diagnosisId, page, size, sort));
  }
}
