package com.kohere.diagnosis.presentation;

import com.kohere.common.response.ApiResponse;
import com.kohere.common.response.PageResponse;
import com.kohere.common.security.AuthPrincipal;
import com.kohere.diagnosis.application.DiagnosisQueryService;
import com.kohere.diagnosis.application.dto.DiagnosisResponse;
import com.kohere.diagnosis.application.dto.LatestDiagnosisResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 확정된 진단을 읽는 REST 컨트롤러(이력·최근·상세). 입력 바인딩·DTO 변환만 담당하고 비즈니스 로직은 응용 계층에 위임한다
 * (docs/convention/code-style.md §3-3). 응답은 공통 래퍼로 감싼다.
 *
 * <p>읽는 대상은 <b>서버 주도 흐름({@code /api/v2/diagnoses/*})이 확정해 저장한 진단</b>이다 — 진단을 시작하고 문항에 답하고 확정하는 경로는
 * 여기에 없다.
 *
 * <p><b>모든 엔드포인트는 인증 필수이며 본인 진단만 접근한다.</b> 인증 주체(userId)는 {@code @AuthenticationPrincipal
 * AuthPrincipal}에서 꺼낸다(ADR-0010). {@code SecurityConfig}에 이 경로용 {@code permitAll} 매처를 두지 않아 비회원 요청은
 * 여기까지 오지 못하므로 {@code principal}이 {@code null}일 수 없다 — 게스트를 열려면 매처를 까는 것만으로는 부족하고 이 역참조부터 고쳐야 한다.
 *
 * <p>스펙: docs/api/specs/02-diagnosis-recommendation.md §4~§6.
 */
@RestController
@RequestMapping("/api/v1/diagnoses")
@RequiredArgsConstructor
public class DiagnosisController {

  private final DiagnosisQueryService diagnosisQueryService;

  /** 내 확정 진단 이력을 최신순 오프셋 페이지로 조회한다. */
  @GetMapping
  public ApiResponse<PageResponse<DiagnosisResponse>> getHistory(
      @AuthenticationPrincipal AuthPrincipal principal,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "submittedAt,desc") String sort) {
    return ApiResponse.success(
        diagnosisQueryService.getHistory(principal.userId(), page, size, sort));
  }

  /** 가장 최근 확정 진단 1건. 확정 진단이 없어도 404가 아니라 {@code completed=false}로 답한다. */
  @GetMapping("/latest")
  public ApiResponse<LatestDiagnosisResponse> getLatest(
      @AuthenticationPrincipal AuthPrincipal principal) {
    return ApiResponse.success(diagnosisQueryService.getLatest(principal.userId()));
  }

  /** 진단 단건 상세. 타인 소유는 {@code 403}, 미존재·폐기 기록·미확정 진단은 {@code 404}다. */
  @GetMapping("/{diagnosisId}")
  public ApiResponse<DiagnosisResponse> getDetail(
      @AuthenticationPrincipal AuthPrincipal principal, @PathVariable Long diagnosisId) {
    return ApiResponse.success(diagnosisQueryService.getDetail(principal.userId(), diagnosisId));
  }
}
