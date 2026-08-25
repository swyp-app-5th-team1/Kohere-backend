package com.kohere.auth.application.dto;

/**
 * 비밀번호 재설정 링크 발송(POST /auth/password/reset-link) 결과. {@code expiresIn}은 링크 유효 시간(초)이다.
 *
 * <p><b>필드가 하나뿐인 것이 설계다.</b> 가입되지 않은 이메일에도 <b>같은 모양·같은 값</b>이 나가야 하므로, "계정을 찾지 못했다"를 담을 필드가 응답에 아예
 * 없다 — 있으면 언젠가 채워지고, 채워지는 순간 이 엔드포인트는 아무 자격 없이 무한히 두드릴 수 있는 <b>완전한 가입 여부 오라클</b>이 된다(§1-8).
 *
 * <p>같은 이유로 마스킹 이메일도 싣지 않는다. 화면은 사용자가 방금 입력한 주소를 이미 알고 있어 서버가 되돌려 줄 이유가 없고, 되돌려 주면 미가입 요청에서 무엇을 실어야
 * 하는지가 곧바로 문제가 된다.
 *
 * <p>docs/api/specs/01-auth-onboarding.md §1-8.
 */
public record PasswordResetLinkResponse(long expiresIn) {}
