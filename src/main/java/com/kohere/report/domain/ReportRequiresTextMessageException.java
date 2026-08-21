package com.kohere.report.domain;

import com.kohere.common.exception.BusinessException;
import com.kohere.common.exception.ErrorCode;

/** 신고자에게 현재 보이는 TEXT 원문이 없어 대화 증거를 만들 수 없는 경우다. */
public class ReportRequiresTextMessageException extends BusinessException {

  public ReportRequiresTextMessageException() {
    super(ErrorCode.REPORT_REQUIRES_TEXT_MESSAGE);
  }
}
