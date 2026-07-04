package com.kohere.auth.presentation.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 토큰 재발급 요청 DTO. 본문 refresh 토큰으로 처리한다(헤더 access 토큰 없이).
 *
 * <p>docs/api/specs/01-auth-onboarding.md §6 (POST /api/v1/auth/reissue).
 */
public record ReissueRequest(@NotBlank String refreshToken) {}
