package com.kohere.user.presentation;

import com.kohere.common.security.AuthPrincipal;
import com.kohere.user.application.UserService;
import com.kohere.user.application.dto.UserProfileResponse;
import com.kohere.user.presentation.dto.UpdateProfileRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내 프로필·계정 REST 컨트롤러(/api/v1/users/me). 인증 주체(userId)는 공통 보안 필터가 주입한 {@link AuthPrincipal}에서 받는다.
 * 보호 경로 인가(ROLE_USER, PENDING 차단)는 보안 계층이 담당한다(ADR-0010). 도메인 DTO만 반환하고, 공통 래퍼는 {@link
 * com.kohere.common.response.ApiResponseWrapper}가 자동 적용한다(ADR-0013).
 *
 * <p>스펙: docs/api/specs/01-auth-onboarding.md §8~10.
 */
@RestController
@RequestMapping("/api/v1/users/me")
@RequiredArgsConstructor
public class UserController {

  private final UserService userService;

  @GetMapping
  public UserProfileResponse getMyProfile(@AuthenticationPrincipal AuthPrincipal principal) {
    return userService.getMyProfile(principal.userId());
  }

  @PatchMapping
  public UserProfileResponse updateMyProfile(
      @AuthenticationPrincipal AuthPrincipal principal,
      @Valid @RequestBody UpdateProfileRequest request) {
    return userService.updateMyProfile(principal.userId(), request);
  }

  @DeleteMapping
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void withdraw(@AuthenticationPrincipal AuthPrincipal principal) {
    userService.withdraw(principal.userId());
  }
}
