package com.kohere.chat.domain;

import com.kohere.common.exception.BusinessException;
import com.kohere.common.exception.ErrorCode;

/** 같은 {@code clientMessageId}를 이전과 다른 본문에 다시 사용한 경우다. */
public class ChatClientMessageConflictException extends BusinessException {

  public ChatClientMessageConflictException() {
    super(ErrorCode.CHAT_CLIENT_MESSAGE_CONFLICT);
  }
}
