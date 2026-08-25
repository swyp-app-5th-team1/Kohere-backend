package com.kohere.auth.presentation.dto;

import com.kohere.common.request.PhoneNumbers;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 이메일 찾기용 연락처 인증번호 발송 요청 DTO(POST /api/v1/auth/phone/find-email/verification-code, 임대인 웹·비로그인).
 * {@code phoneNumber}는 인증번호를 받을 휴대폰 번호로, 하이픈은 있어도 없어도 된다 — 응용 계층이 숫자만 남겨 정규화한 값을 챌린지 키로 쓴다({@link
 * PhoneNumbers}).
 *
 * <p>형식 위반은 {@code INVALID_INPUT}으로 <b>SMS 발송·레이트리밋 카운트 이전에</b> 거른다 — 걸러 내지 않으면 형식이 깨진 값이 그대로 Redis
 * 키가 되고 발송비까지 나간다. docs/api/specs/01-auth-onboarding.md §1-5.
 */
public record FindEmailPhoneVerificationCodeRequest(
    @NotBlank @Pattern(regexp = PhoneNumbers.PATTERN) String phoneNumber) {}
