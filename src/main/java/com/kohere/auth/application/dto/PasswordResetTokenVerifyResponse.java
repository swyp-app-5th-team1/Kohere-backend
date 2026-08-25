package com.kohere.auth.application.dto;

/**
 * 재설정 토큰 사전 확인(POST /auth/password/reset-token/verify) 결과.
 *
 * <p>{@code email}은 토큰이 가리키는 계정의 로그인 이메일을 <b>마스킹</b>한 값이다 — 토큰만 있으면 부를 수 있는 경로라 평문을 실으면 <b>유출된 링크
 * 하나가 계정 이메일까지 함께 넘긴다</b>. 화면이 "이 계정의 비밀번호를 바꿉니다"를 확인시키는 데는 마스킹으로 충분하다.
 *
 * <p>{@code expiresIn}은 <b>남은 초</b>다 — 발급 시 고정값(1800)이 아니라 호출 시점 기준 잔여라 호출할 때마다 줄어든다. 화면의 카운트다운은 이
 * 값에서 시작한다.
 *
 * <p>docs/api/specs/01-auth-onboarding.md §1-9.
 */
public record PasswordResetTokenVerifyResponse(String email, long expiresIn) {}
