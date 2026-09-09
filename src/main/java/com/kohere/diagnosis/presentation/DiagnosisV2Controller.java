package com.kohere.diagnosis.presentation;

import com.kohere.common.response.ApiResponse;
import com.kohere.common.security.AuthPrincipal;
import com.kohere.common.security.AuthPrincipals;
import com.kohere.diagnosis.application.DiagnosisFlowService;
import com.kohere.diagnosis.application.dto.DiagnosisFlowResponse;
import com.kohere.diagnosis.application.dto.V2RecommendationMapResponse;
import com.kohere.diagnosis.application.dto.V2RecommendationResponse;
import com.kohere.diagnosis.presentation.dto.AnswerRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * v2 서버 주도 진단 REST 컨트롤러(issue #157·ADR-0036). 클라이언트가 {@code POST /start}로 진단을 시작하고 {@code POST
 * /next}로 답을 이어 보내면, 서버가 {@code step} 없이 다음 질문·분기·확정 시점을 판단한다. v1({@code /api/v1/diagnoses/*})은 그대로
 * 유지된다.
 *
 * <p>시작은 클라이언트가 주도한다 — {@code /start}는 진행 중 세션이 있어도 버리고 처음부터 시작하며, 세션 없이 온 {@code /next}는 400으로
 * 막는다. 확정 매물 조회 시점도 클라이언트가 정하며, {@link #recommendations}를 {@code diagnosisId}로 호출한다.
 *
 * <p>입력 바인딩·응답 래핑만 담당하고 로직은 {@link DiagnosisFlowService}에 위임한다.
 *
 * <p><b>인증은 선택이다</b>(#181) — 네 엔드포인트 모두 {@code permitAll}이라 비회원(게스트)도 호출한다. 토큰이 없으면
 * {@code @AuthenticationPrincipal}이 주입하는 주체 자체가 {@code null}이므로 {@code principal.userId()}를 직접
 * 역참조하지 않고 {@link AuthPrincipals#userIdOrNull}로 꺼낸다(ADR-0010). 게스트의 요청 간 연속성은 {@code
 * X-Guest-Session-Id} 헤더가 잇는다 — 회원은 보내지 않으며, 실려 와도 응용 계층이 무시한다.
 *
 * <p>스펙: docs/api/specs/02-diagnosis-recommendation.md (v2 · 게스트 접근) · 시퀀스 US-2-7.
 */
@RestController
@RequestMapping("/api/v2/diagnoses")
@RequiredArgsConstructor
public class DiagnosisV2Controller {

  /** 게스트 세션 키를 싣는 요청 헤더 이름. 발급은 {@code /start} 응답이고, 이 헤더는 그 키의 에코다(#181). */
  private static final String GUEST_SESSION_HEADER = "X-Guest-Session-Id";

  private final DiagnosisFlowService diagnosisFlowService;

  /**
   * 진단을 처음부터 시작하고 ① 지역 질문을 반환한다(진행 중 세션이 있으면 버린다).
   *
   * <p>게스트 세션 키의 <b>유일한 발급 지점</b>이다 — 토큰 없이 오면 응답 {@code data.guestSessionId}에 새 키가 실린다(회원 응답에서는
   * 생략). 요청에 이 헤더가 실려 와도 무시하고 새 키를 발급한다.
   */
  @PostMapping("/start")
  public ApiResponse<DiagnosisFlowResponse> start(
      @AuthenticationPrincipal AuthPrincipal principal) {
    return ApiResponse.success(diagnosisFlowService.start(AuthPrincipals.userIdOrNull(principal)));
  }

  /**
   * 현재 문항 답을 적용하고 다음 결과를 결과코드로 반환한다.
   *
   * <p>게스트는 {@code X-Guest-Session-Id}가 진행 세션을 찾는 유일한 키다 — 빠뜨렸거나 남의 키면 세션을 찾지 못해 {@code 400
   * DIAGNOSIS_SESSION_NOT_FOUND}이며, 클라이언트가 {@code /start}로 복구한다.
   */
  @PostMapping("/next")
  public ApiResponse<DiagnosisFlowResponse> next(
      @AuthenticationPrincipal AuthPrincipal principal,
      @RequestHeader(name = GUEST_SESSION_HEADER, required = false) String guestSessionId,
      @RequestBody(required = false) AnswerRequest request) {
    return ApiResponse.success(
        diagnosisFlowService.next(AuthPrincipals.userIdOrNull(principal), guestSessionId, request));
  }

  /**
   * 확정 진단의 추천 매물·지도 좌표를 조회한다. 이 호출 자체가 "매물을 받겠다"는 클라이언트의 결정이며, 0건이면 빈 {@code content}가 곧 no-match다.
   *
   * <p>소유권은 신원 종류가 같고 값이 같을 때만 통과한다 — 게스트는 {@code X-Guest-Session-Id}가 필수이며, 회원↔게스트 교차 조회는 양방향 모두
   * {@code 403 FORBIDDEN}이다.
   */
  @GetMapping("/{diagnosisId}/recommendations")
  public ApiResponse<V2RecommendationResponse> recommendations(
      @AuthenticationPrincipal AuthPrincipal principal,
      @RequestHeader(name = GUEST_SESSION_HEADER, required = false) String guestSessionId,
      @PathVariable Long diagnosisId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String sort) {
    return ApiResponse.success(
        diagnosisFlowService.getRecommendations(
            AuthPrincipals.userIdOrNull(principal), guestSessionId, diagnosisId, page, size, sort));
  }

  /**
   * 확정 진단의 추천 매물 지도 마커를 <b>페이지 없이</b> 조회한다. 매칭 조건은 위 추천 조회와 같고 응답에서 카드 정보가 빠질 뿐이다.
   *
   * <p>서버 상한(500건)을 넘으면 잘라서 준다 — 진단은 조건이 고정이라 클라이언트가 범위를 좁힐 수단이 없어 오류로 만들지 않는다. 잘렸는지는 {@code
   * markers} 길이와 {@code total}을 비교해 안다.
   *
   * <p>이 경로만 <b>확정 진단</b>을 요구한다(미확정은 404) — 페이지 상한이 없어 조건이 빈 초안이 곧 전체 매물 조회가 되기 때문이다.
   */
  @GetMapping("/{diagnosisId}/recommendations/map")
  public ApiResponse<V2RecommendationMapResponse> recommendationMarkers(
      @AuthenticationPrincipal AuthPrincipal principal,
      @RequestHeader(name = GUEST_SESSION_HEADER, required = false) String guestSessionId,
      @PathVariable Long diagnosisId) {
    return ApiResponse.success(
        diagnosisFlowService.getRecommendationMarkers(
            AuthPrincipals.userIdOrNull(principal), guestSessionId, diagnosisId));
  }
}
