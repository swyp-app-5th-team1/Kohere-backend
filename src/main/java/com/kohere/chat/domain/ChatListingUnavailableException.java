package com.kohere.chat.domain;

import com.kohere.common.exception.BusinessException;
import com.kohere.common.exception.ErrorCode;

/** 문의할 매물이 없거나 공개 상태가 아닌 경우. 전역 핸들러가 404 {@code LISTING_NOT_FOUND}로 변환한다. */
public class ChatListingUnavailableException extends BusinessException {

  public ChatListingUnavailableException() {
    super(ErrorCode.LISTING_NOT_FOUND);
  }
}
