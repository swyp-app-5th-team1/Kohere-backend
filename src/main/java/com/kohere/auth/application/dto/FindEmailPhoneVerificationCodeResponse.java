package com.kohere.auth.application.dto;

/**
 * 이메일 찾기용 연락처 인증번호 발송(POST /auth/phone/find-email/verification-code) 결과. {@code phoneNumber}는 마스킹해
 * 반환하고, {@code expiresIn}은 인증번호 만료까지의 초다. 인증번호 원문은 응답·로그에 노출하지 않는다.
 *
 * <p>가입 이력이 있는 번호든 없는 번호든 <b>같은 모양·같은 값</b>이 나간다 — 여기서 걸러 주면 SMS를 받아 볼 필요도 없이 <b>번호 열거</b>가 가능해진다.
 * 그 번호로 가입된 웹 계정이 있는지는 이름까지 받아 보는 §1-7이 판정한다. docs/api/specs/01-auth-onboarding.md §1-5.
 */
public record FindEmailPhoneVerificationCodeResponse(String phoneNumber, long expiresIn) {}
