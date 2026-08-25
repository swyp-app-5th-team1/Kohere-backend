package com.kohere.auth.application.dto;

/**
 * 가입 이메일 찾기(POST /auth/email/find) 결과 — <b>마스킹된</b> 웹 로그인 ID.
 *
 * <p><b>담는 값은 {@code local_accounts.email}이다</b>(프로필 이메일 {@code users.email}이 아니다). 이 화면에서 사용자가 알아야
 * 하는 것은 프로필 이메일이 아니라 <b>로그인 화면에 입력할 ID</b>이고, 연동된 계정은 두 값이 다를 수 있어 정본을 잘못 고르면 찾아 준 이메일로 로그인이 되지 않아
 * 화면 전체가 무용해진다.
 *
 * <p><b>평문을 싣지 않는다</b> — 이 응답은 본인 확인 결과가 아니라 <b>번호 소유 증명 뒤의 힌트</b>다. 로그인 화면에서 나머지를 기억해 채우는 데는 마스킹으로
 * 충분하고, 그 이상은 번호를 손에 쥔 제3자에게도 그대로 넘어간다. docs/api/specs/01-auth-onboarding.md §1-7.
 */
public record FindEmailResponse(String email) {}
