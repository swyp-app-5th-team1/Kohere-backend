package com.kohere.chat.domain;

import com.kohere.common.exception.BusinessException;
import com.kohere.common.exception.ErrorCode;

/** 매물 문의를 세입자가 아닌 사용자가 요청한 경우. 전역 핸들러가 공통 403 {@code FORBIDDEN}으로 변환한다. */
public class ChatTenantOnlyException extends BusinessException {

  public ChatTenantOnlyException() {
    super(ErrorCode.FORBIDDEN);
  }
}
