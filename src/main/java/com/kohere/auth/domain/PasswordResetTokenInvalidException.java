package com.kohere.auth.domain;

import com.kohere.common.exception.BusinessException;
import com.kohere.common.exception.ErrorCode;

/**
 * 비밀번호 재설정 토큰이 없거나·만료됐거나·이미 사용됨. 전역 핸들러가 422 {@code AUTH_PASSWORD_RESET_TOKEN_INVALID}로 변환한다(스펙
 * §1-9·§1-10).
 *
 * <p><b>세 경우를 구분하지 않는 것이 계약이다.</b> "없음"과 "이미 사용됨"을 갈라 주면 그것만으로 <b>어떤 토큰이 실재했는지</b>를 알려 주는 오라클이 되고,
 * 사전 확인(§1-9)은 소비하지 않는 경로라 그 오라클을 몇 번이고 두드릴 수 있다. 사용자가 할 일은 세 경우 모두 같다 — 링크를 다시 받는 것이다.
 *
 * <p>토큰이 가리키는 {@code userId}에 웹 자격증명이 남아 있지 않은 경우(탈퇴로 {@code local_accounts} 행이 지워졌다)도 같은 예외다. 거기서
 * 404 {@code AUTH_WEB_ACCOUNT_NOT_FOUND}로 갈라 주면 "이 링크는 진짜였고 계정만 사라졌다"까지 알려 주는 셈이라, 위와 같은 이유로 수렴시킨다.
 */
public class PasswordResetTokenInvalidException extends BusinessException {

  public PasswordResetTokenInvalidException() {
    super(ErrorCode.AUTH_PASSWORD_RESET_TOKEN_INVALID);
  }
}
