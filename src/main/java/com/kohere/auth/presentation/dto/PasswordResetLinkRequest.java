package com.kohere.auth.presentation.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 비밀번호 재설정 링크 발송 요청 DTO(POST /api/v1/auth/password/reset-link, 비로그인). 필드가 <b>이메일 하나</b>다 — 이름·연락처
 * 같은 추가 확인 값을 받지 않는다.
 *
 * <p><b>왜 이름을 받지 않는가</b> — 이 요청으로 일어나는 일은 "그 주소로 메일을 보내는 것"뿐이고, 메일함을 여는 사람만 다음 단계로 갈 수 있으므로 소유 증명은
 * <b>메일 수신 자체</b>가 한다. 이름을 더해도 막히는 공격이 없는 반면, 이름 대조로 응답을 가르면 그 자리에서 계정 열거 오라클이 생긴다(이메일 찾기 §1-7이 이름을
 * 받는 것은 그쪽에 SMS 인증 마커라는 선행 게이트가 있어서다).
 *
 * <p>{@code email}은 {@code local_accounts.email}(웹 로그인 ID)로만 조회하며 {@code users.email}은 보지 않는다 — 재설정
 * 대상은 프로필이 아니라 웹 자격증명이다. 길이 상한은 저장 컬럼(V22, VARCHAR(255))과 맞춘다.
 *
 * <p><b>형식만 맞으면 등록 여부와 무관하게 200</b>이다. 검증에서 걸리는 것은 형식뿐이고, 그 뒤로는 응답이 갈리지 않는다(§1-8).
 *
 * <p>docs/api/specs/01-auth-onboarding.md §1-8.
 */
public record PasswordResetLinkRequest(@NotBlank @Size(max = 255) @Email String email) {}
