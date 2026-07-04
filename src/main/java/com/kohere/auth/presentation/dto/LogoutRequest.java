package com.kohere.auth.presentation.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 로그아웃 요청 DTO. 무효화할 refresh 토큰을 담는다.
 *
 * <p>docs/api/specs/01-auth-onboarding.md §7 (POST /api/v1/auth/logout).
 */
public record LogoutRequest(@NotBlank String refreshToken) {}
