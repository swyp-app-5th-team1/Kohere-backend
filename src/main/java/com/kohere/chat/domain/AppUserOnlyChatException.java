package com.kohere.chat.domain;

import com.kohere.common.exception.BusinessException;
import com.kohere.common.exception.ErrorCode;

/**
 * 세입자·임대인이 아닌 회원(관리자 등)이 사용자용 기능을 호출했을 때 던진다.
 *
 * <p>공유 커널에 공통 예외를 두지 않는 이유는 모듈 경계다 — 도메인 예외는 각 모듈이 소유하고, 공유 커널은 {@link ErrorCode}만 나눠 쓴다. 응답은 어느
 * 모듈에서든 {@link ErrorCode#FORBIDDEN}으로 같다.
 */
public class AppUserOnlyChatException extends BusinessException {

  public AppUserOnlyChatException() {
    super(ErrorCode.FORBIDDEN);
  }
}
