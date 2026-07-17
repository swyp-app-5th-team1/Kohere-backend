package com.kohere.lifetip.domain;

import com.kohere.common.exception.BusinessException;
import com.kohere.common.exception.ErrorCode;

/**
 * 세입자(userType=TENANT) 전용 기능에 임대인 등 비세입자가 접근한 경우. 전역 핸들러가 403 {@code FORBIDDEN}으로 변환한다. 생활 팁은 한국 생활
 * 정보가 필요한 외국인 세입자 대상 기능이다(US-8). 임대인도 {@code country}·{@code lang}을 갖지만(#141) 대상 액터 정의상 제외된다.
 */
public class TenantOnlyException extends BusinessException {

  public TenantOnlyException() {
    super(ErrorCode.FORBIDDEN);
  }
}
