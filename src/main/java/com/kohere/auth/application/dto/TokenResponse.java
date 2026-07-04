package com.kohere.auth.application.dto;

/**
 * 토큰 발급 결과. 온보딩 완료·재발급 시 정식 access/refresh 토큰을 담는다. {@code expiresIn}은 access 토큰 만료까지의 초(seconds).
 *
 * <p>docs/api/specs/01-auth-onboarding.md §5·§6 (onboarding·reissue 응답).
 */
public record TokenResponse(
    String tokenType, String accessToken, String refreshToken, long expiresIn) {}
