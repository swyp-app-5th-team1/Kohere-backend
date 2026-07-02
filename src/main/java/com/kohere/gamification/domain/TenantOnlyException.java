package com.kohere.gamification.domain;

import com.kohere.common.exception.BusinessException;
import com.kohere.common.exception.ErrorCode;

/**
 * 세입자(userType=TENANT) 전용 기능에 임대인이 접근한 경우. 전역 핸들러가 403 {@code FORBIDDEN}으로 변환한다. 학습 퀴즈는 외국인 세입자
 * 대상이다(ADR-0035).
 */
public class TenantOnlyException extends BusinessException {

  public TenantOnlyException() {
    super(ErrorCode.FORBIDDEN);
  }
}
