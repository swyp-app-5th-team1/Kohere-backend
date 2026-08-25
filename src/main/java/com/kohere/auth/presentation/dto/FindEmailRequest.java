package com.kohere.auth.presentation.dto;

import com.kohere.common.request.PhoneNumbers;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 가입 이메일 찾기 요청 DTO(POST /api/v1/auth/email/find, 임대인 웹·비로그인). {@code phoneNumber}는 §1-5·§1-6으로
 * <b>인증을 마친 번호</b>여야 하며(마커 부재·만료는 422 {@code AUTH_PHONE_NOT_VERIFIED}), 정규화 후 비교하므로 하이픈 표기는
 * 무관하다({@link PhoneNumbers}).
 *
 * <p>{@code name}은 {@code local_accounts.name}과 대조할 값이다 — 불일치는 계정 미존재와 <b>같은 404</b>다(이름 오라클 차단).
 * 길이 상한은 저장 컬럼과 같은 200자로 두고, 그보다 긴 값은 어차피 어떤 저장 값과도 맞을 수 없으므로 조회 전에 {@code INVALID_INPUT}으로 거른다.
 * docs/api/specs/01-auth-onboarding.md §1-7.
 */
public record FindEmailRequest(
    @NotBlank @Pattern(regexp = PhoneNumbers.PATTERN) String phoneNumber,
    @NotBlank @Size(max = 200) String name) {}
