package com.kohere.chat.domain;

import com.kohere.common.exception.BusinessException;
import com.kohere.common.exception.ErrorCode;

/** 사용자 TEXT가 제품 정책인 Unicode code point 3,000자를 넘은 경우다. */
public class ChatMessageTooLongException extends BusinessException {

  public ChatMessageTooLongException() {
    super(ErrorCode.CHAT_MESSAGE_TOO_LONG);
  }
}
