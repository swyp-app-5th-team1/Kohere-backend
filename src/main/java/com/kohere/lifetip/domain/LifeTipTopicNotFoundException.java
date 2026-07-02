package com.kohere.lifetip.domain;

import com.kohere.common.exception.BusinessException;
import com.kohere.common.exception.ErrorCode;

/** 요청한 생활 팁 주제가 존재하지 않는 경우. 전역 핸들러가 404 {@code LIFE_TIP_TOPIC_NOT_FOUND}로 변환한다. */
public class LifeTipTopicNotFoundException extends BusinessException {

  public LifeTipTopicNotFoundException() {
    super(ErrorCode.LIFE_TIP_TOPIC_NOT_FOUND);
  }
}
