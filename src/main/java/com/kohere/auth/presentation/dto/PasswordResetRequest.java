package com.kohere.auth.presentation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 비밀번호 재설정 확정 요청 DTO(POST /api/v1/auth/password/reset, 비로그인).
 *
 * <p><b>비밀번호 정책은 가입(§1-3 {@code password})과 같은 상수를 공유한다</b>({@link SignupRequest#PASSWORD_PATTERN})
 * — 사본을 두면 언젠가 한쪽만 바뀌어 <b>가입 때 통과하던 비밀번호가 복구 화면에서 거부되거나 그 반대</b>가 되고, 사용자는 규칙이 둘이라는 사실 자체를 알 길이 없다.
 * 정규식 문자열을 두 번 적지 않는 것이 그 정합성을 지키는 가장 싼 방법이다(같은 패키지라 참조할 수 있다).
 *
 * <p>위반 시 {@code 400 INVALID_INPUT} + {@code errors[].field=newPassword}이며, 사유 문구는 {@code
 * Pattern.passwordResetRequest.newPassword} 키로 두 번들(영어·한국어)에서 해소한다 — 그래서 {@code @Pattern}에 {@code
 * message} 속성을 두지 않는다(ADR-0030: {@code MessageSource}가 키를 먼저 찾아 이기므로 애너테이션 문구는 죽은 코드가 된다).
 *
 * <p><b>정책 위반(400)은 토큰을 소비하기 전에 걸러진다</b> — Bean Validation이 컨트롤러 진입 시점에 판정하므로 확정 처리 순서의 ①(토큰 원자
 * 소비)에 닿지 않는다. 오타 한 번에 링크가 죽으면 사용자는 규칙을 배우는 동안 메일을 반복해서 받아야 한다.
 *
 * <p>{@code newPassword} 원문은 <b>BCrypt 해시로만 보관</b>하고 저장·로그·응답 어디에도 남기지 않는다(그래서 이 record에 {@code
 * toString} 재정의를 기대지 않고, 요청 본문을 로깅하는 필터를 두지 않는다).
 *
 * <p>docs/api/specs/01-auth-onboarding.md §1-10.
 */
public record PasswordResetRequest(
    @NotBlank String token,
    @NotBlank @Pattern(regexp = SignupRequest.PASSWORD_PATTERN) String newPassword) {}
