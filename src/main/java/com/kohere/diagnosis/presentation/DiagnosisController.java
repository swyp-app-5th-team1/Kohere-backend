package com.kohere.diagnosis.presentation;

import com.kohere.common.response.ApiResponse;
import com.kohere.common.response.PageResponse;
import com.kohere.common.security.AuthPrincipal;
import com.kohere.diagnosis.application.DiagnosisService;
import com.kohere.diagnosis.application.dto.AnswerSavedResponse;
import com.kohere.diagnosis.application.dto.DiagnosisCreatedResponse;
import com.kohere.diagnosis.application.dto.DiagnosisResponse;
import com.kohere.diagnosis.application.dto.LatestDiagnosisResponse;
import com.kohere.diagnosis.application.dto.QuestionResponse;
import com.kohere.diagnosis.application.dto.RecommendationResponse;
import com.kohere.diagnosis.presentation.dto.AnswerRequest;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 맞춤 진단·매물 추천 REST 컨트롤러. 입력 바인딩·DTO 변환만 담당하고 비즈니스 로직은 응용 계층에 위임한다(docs/convention/code-style.md
 * §3-3). 응답은 공통 래퍼로 감싼다.
 *
 * <p>모든 엔드포인트는 인증 필수이며 본인 진단만 접근한다. 인증 주체(userId)는 {@code @AuthenticationPrincipal AuthPrincipal}에서
 * 꺼낸다(ADR-0010). 단계별 진단은 서버 stateful이다 — 문항 조회({@code GET /questions/{step}})·답 저장({@code POST
 * /answers})으로 진행 중 진단을 채우고 확정({@code POST /diagnoses})한다.
 *
 * <p>스펙: docs/api/specs/02-diagnosis-recommendation.md.
 */
@RestController
@RequestMapping("/api/v1/diagnoses")
@RequiredArgsConstructor
public class DiagnosisController {

  private final DiagnosisService diagnosisService;

  @PostMapping
  public ResponseEntity<ApiResponse<DiagnosisCreatedResponse>> submit(
      @AuthenticationPrincipal AuthPrincipal principal) {
    DiagnosisCreatedResponse created = diagnosisService.submit(principal.userId());
    return ResponseEntity.created(URI.create("/api/v1/diagnoses/" + created.diagnosisId()))
        .body(ApiResponse.success(created));
  }

  @GetMapping("/questions/{step}")
  public ApiResponse<QuestionResponse> getQuestion(
      @AuthenticationPrincipal AuthPrincipal principal, @PathVariable int step) {
    return ApiResponse.success(diagnosisService.getQuestion(principal.userId(), step));
  }

  @PostMapping("/answers")
  public ApiResponse<AnswerSavedResponse> submitAnswer(
      @AuthenticationPrincipal AuthPrincipal principal, @RequestBody AnswerRequest request) {
    return ApiResponse.success(diagnosisService.submitAnswer(principal.userId(), request));
  }

  @GetMapping
  public ApiResponse<PageResponse<DiagnosisResponse>> getHistory(
      @AuthenticationPrincipal AuthPrincipal principal,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "submittedAt,desc") String sort) {
    return ApiResponse.success(diagnosisService.getHistory(principal.userId(), page, size, sort));
  }

  @GetMapping("/latest")
  public ApiResponse<LatestDiagnosisResponse> getLatest(
      @AuthenticationPrincipal AuthPrincipal principal) {
    return ApiResponse.success(diagnosisService.getLatest(principal.userId()));
  }

  @GetMapping("/{diagnosisId}")
  public ApiResponse<DiagnosisResponse> getDetail(
      @AuthenticationPrincipal AuthPrincipal principal, @PathVariable Long diagnosisId) {
    return ApiResponse.success(diagnosisService.getDetail(principal.userId(), diagnosisId));
  }

  @GetMapping("/{diagnosisId}/recommendations")
  public ApiResponse<RecommendationResponse> getRecommendations(
      @AuthenticationPrincipal AuthPrincipal principal,
      @PathVariable Long diagnosisId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "recommended,desc") String sort) {
    return ApiResponse.success(
        diagnosisService.getRecommendations(principal.userId(), diagnosisId, page, size, sort));
  }
}
