package com.kohere.diagnosis.domain;

import com.kohere.common.exception.BusinessException;
import com.kohere.common.exception.ErrorCode;

/**
 * 타인 소유 진단에 접근할 때(403 FORBIDDEN). 진단 단건·추천 조회는 본인 소유만 허용한다(스펙
 * docs/api/specs/02-diagnosis-recommendation.md).
 */
public class DiagnosisAccessDeniedException extends BusinessException {

  public DiagnosisAccessDeniedException() {
    super(ErrorCode.FORBIDDEN);
  }
}
