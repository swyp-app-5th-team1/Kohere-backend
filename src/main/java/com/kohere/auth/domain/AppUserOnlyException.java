package com.kohere.auth.domain;

import com.kohere.common.exception.BusinessException;
import com.kohere.common.exception.ErrorCode;

/**
 * 세입자·임대인이 아닌 회원(관리자 등)이 회원용 인증 기능을 호출했을 때 던진다.
 *
 * <p>관리자를 콕 집어 거부하지 않고 아는 두 유형만 통과시키는 허용 목록이라, 나중에 회원 유형이 늘어도 자동으로 거부된다. 다른 모듈의 같은 이름 예외와 뜻이 같고 응답도
 * {@link ErrorCode#FORBIDDEN}으로 같다 — 도메인 예외는 각 모듈이 소유하므로 공유하지 않는다.
 */
public class AppUserOnlyException extends BusinessException {

  public AppUserOnlyException() {
    super(ErrorCode.FORBIDDEN);
  }
}
