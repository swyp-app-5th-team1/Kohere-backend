package com.kohere.chat.infrastructure.websocket;

import com.kohere.common.exception.ErrorCode;
import org.springframework.security.core.AuthenticationException;

/** STOMP CONNECT 인증 실패를 안전한 공통 코드로 전달하는 내부 예외다. JWT나 원본 header는 절대 보관하지 않는다. */
public class ChatStompAuthenticationException extends AuthenticationException {

  private final ErrorCode errorCode;

  public ChatStompAuthenticationException(ErrorCode errorCode) {
    super(errorCode.getDefaultMessage());
    this.errorCode = errorCode;
  }

  /** 프런트엔드가 문자열 메시지가 아니라 안정적인 코드로 분기할 수 있도록 공통 코드를 반환한다. */
  public ErrorCode getErrorCode() {
    return errorCode;
  }
}
