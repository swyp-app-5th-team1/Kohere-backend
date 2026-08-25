package com.kohere.auth.application.dto;

/**
 * 이메일 찾기용 연락처 인증번호 확인(POST /auth/phone/find-email/verify) 결과. {@code phoneNumber}는 마스킹, {@code
 * verified}는 성공 시 true다(실패는 응답이 아니라 422 {@code AUTH_PHONE_VERIFICATION_FAILED}로 나가므로 false가 실리는 경우는
 * 없다).
 *
 * <p><b>이 응답은 계정의 유무를 말하지 않는다</b> — "번호를 검증했다"는 사실만 알린다. 여기서 "그 번호로 가입된 계정이 없다"를 미리 알려 주면 §1-5·§1-6
 * 두 번만으로 번호 열거가 성립하고, §1-7의 이름 대조 게이트를 통째로 건너뛰게 된다. docs/api/specs/01-auth-onboarding.md §1-6.
 */
public record FindEmailPhoneVerifyResponse(String phoneNumber, boolean verified) {}
