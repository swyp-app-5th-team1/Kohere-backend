package com.kohere.lifetip.presentation;

import com.kohere.common.response.ApiResponse;
import com.kohere.common.security.AuthPrincipal;
import com.kohere.lifetip.application.LifeTipService;
import com.kohere.lifetip.application.dto.TipListResponse;
import com.kohere.lifetip.application.dto.TopicListResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 생활 팁(주제별 생활 정보) REST 컨트롤러. 입력 바인딩·DTO 변환만 담당하고 비즈니스 로직은 응용 계층에 위임한다(code-style §3-3). 응답은 공통 래퍼로
 * 감싼다.
 *
 * <p>모든 엔드포인트는 정식 인증(ROLE_USER = ACTIVE 세입자) 필수다 — 표시 언어를 등록 국가에서 도출하려면 온보딩이 완료되어야 한다(US-8,
 * SecurityConfig 정식 인증 티어). 인증 주체(userId)는 {@code @AuthenticationPrincipal AuthPrincipal}에서
 * 꺼낸다(ADR-0010).
 *
 * <p>스펙: docs/api/specs/08-life-tips.md.
 */
@RestController
@RequestMapping("/api/v1/life-tips")
@RequiredArgsConstructor
public class LifeTipController {

  private final LifeTipService lifeTipService;

  @GetMapping("/topics")
  public ApiResponse<TopicListResponse> getTopics(
      @AuthenticationPrincipal AuthPrincipal principal) {
    return ApiResponse.success(lifeTipService.getTopics(principal.userId()));
  }

  @GetMapping("/topics/{topicCode}/tips")
  public ApiResponse<TipListResponse> getTips(
      @AuthenticationPrincipal AuthPrincipal principal, @PathVariable String topicCode) {
    return ApiResponse.success(lifeTipService.getTips(principal.userId(), topicCode));
  }
}
