package com.kohere.chat.domain;

import com.kohere.common.exception.BusinessException;
import com.kohere.common.exception.ErrorCode;

/** 두 참여자 사이 어느 방향이든 차단 관계가 있어 새 채팅을 시작할 수 없는 경우. */
public class ChatUnavailableException extends BusinessException {

  public ChatUnavailableException() {
    super(ErrorCode.CHAT_UNAVAILABLE);
  }
}
