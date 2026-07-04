package com.kohere.auth.application.dto;

import com.kohere.user.api.UserProfileView;

/**
 * 온보딩 완료 응답. 완성된 회원 프로필({@code user})과 정식 access/refresh 토큰을 함께 반환한다. {@code expiresIn}은 access 토큰
 * 만료까지의 초(seconds).
 *
 * <p>docs/api/specs/01-auth-onboarding.md §5·§5-2 (onboarding 응답) · 시퀀스 US-1-2와 정합.
 */
public record OnboardingResponse(
    UserProfileView user,
    String tokenType,
    String accessToken,
    String refreshToken,
    long expiresIn) {}
