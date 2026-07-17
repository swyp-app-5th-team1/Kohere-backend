package com.kohere.diagnosis.domain;

import com.kohere.common.exception.BusinessException;
import com.kohere.common.exception.ErrorCode;

/**
 * v2 서버 주도 흐름에서 진행 중 세션 없이 {@code POST /next}가 온 경우(앱 재시작·터미널 후 재전송·만료). 전역 핸들러가 400 {@code
 * DIAGNOSIS_SESSION_NOT_FOUND}로 변환한다.
 *
 * <p>서버가 임의로 흐름을 되살리지 않는다 — 진단 시작은 클라이언트가 {@code POST /start}로 주도한다(ADR-0036).
 */
public class DiagnosisFlowSessionNotFoundException extends BusinessException {

  public DiagnosisFlowSessionNotFoundException() {
    super(ErrorCode.DIAGNOSIS_SESSION_NOT_FOUND);
  }
}
