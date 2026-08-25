package com.kohere.auth.presentation.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 재설정 토큰 사전 확인 요청 DTO(POST /api/v1/auth/password/reset-token/verify, 비로그인).
 *
 * <p><b>토큰을 쿼리 파라미터가 아니라 본문으로 받는다.</b> 쿼리스트링은 액세스 로그·리퍼러 헤더·프록시 로그에 <b>원문 그대로</b> 남는데, 이 값 하나로 남의
 * 비밀번호를 바꿀 수 있다. 링크 자체가 쿼리에 토큰을 싣는 것은 어쩔 수 없지만(사용자가 클릭해야 한다) 서버 API까지 같은 실수를 반복할 이유가 없다.
 *
 * <p>형식 검증은 {@code @NotBlank}뿐이다 — 접두({@code pr_})나 길이를 여기서 강제하면 <b>모양만으로 걸러진 요청</b>과 실제로 없는 토큰이 다른
 * 응답(400 vs 422)을 받아, 그 차이가 "토큰의 생김새"를 알려 주는 신호가 된다. 유효성은 해시 대조 한 곳에서만 판정한다.
 *
 * <p>docs/api/specs/01-auth-onboarding.md §1-9.
 */
public record PasswordResetTokenVerifyRequest(@NotBlank String token) {}
