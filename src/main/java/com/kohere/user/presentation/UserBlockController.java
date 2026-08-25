package com.kohere.user.presentation;

import com.kohere.common.response.PageResponse;
import com.kohere.common.security.AuthPrincipal;
import com.kohere.user.api.BlockedUserView;
import com.kohere.user.api.UserBlockService;
import com.kohere.user.application.AppUserGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내 차단 목록·해제 REST 컨트롤러(/api/v1/users/me/blocks). 차단 목록 조회와 해제를 제공한다 — 차단 생성은 예약에서 상대를 도출해야 해
 * booking(POST /api/v1/bookings/{bookingId}/block)이 소유하고, 여기서는 예약과 무관한 목록·해제만 다룬다(차단하면 그 예약이 목록에서
 * 사라져 bookingId로는 해제 경로를 만들 수 없다).
 *
 * <p>도메인/뷰 DTO만 반환하고 공통 래퍼는 {@code ApiResponseWrapper}가 자동 적용한다(ADR-0013). 인가(ROLE_USER)는 보안 계층이
 * 담당한다. 스펙: docs/api/specs/01-auth-onboarding.md §11·§12.
 */
@RestController
@RequestMapping("/api/v1/users/me/blocks")
@RequiredArgsConstructor
public class UserBlockController {

  private final UserBlockService userBlockService;

  /**
   * 세입자·임대인만 통과시킨다(US-3-7).
   *
   * <p>여기서만 응용 계층이 아니라 표현 계층에 게이트를 둔다 — {@code UserBlockService}는 {@code user :: api}의 <b>모듈 간 공개
   * 계약</b>이라 booking·chat이 차단 여부를 물으려고 호출한다. 구현에 게이트를 넣으면 그 내부 호출까지 함께 막힌다.
   */
  private final AppUserGuard appUserGuard;

  @GetMapping
  public PageResponse<BlockedUserView> getMyBlocks(
      @AuthenticationPrincipal AuthPrincipal principal,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    appUserGuard.requireAppUser(principal.userId());
    return userBlockService.listBlocks(principal.userId(), page, size);
  }

  @DeleteMapping("/{userId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void unblock(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable long userId) {
    appUserGuard.requireAppUser(principal.userId());
    userBlockService.unblock(principal.userId(), userId);
  }
}
