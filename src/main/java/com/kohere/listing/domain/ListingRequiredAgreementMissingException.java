package com.kohere.listing.domain;

import com.kohere.common.exception.BusinessException;
import com.kohere.common.exception.ErrorCode;

/**
 * 매물 등록 요청의 이용약관 동의 2종 중 하나 이상이 누락되거나 {@code false}일 때 던진다.
 *
 * <p>값의 형식이 아니라 <b>「동의하지 않았다」는 의미상 거부</b>라 {@code INVALID_INPUT}(400)이 아니라 422다 — 회원 약관의 {@code
 * AUTH_REQUIRED_AGREEMENT_MISSING}과 같은 성격이라 status를 맞춘다.
 *
 * <p>이 게이트 덕분에 <b>저장된 매물은 예외 없이 동의를 마친 매물</b>이 되고, 심사 단계가 동의 여부를 판단 기준으로 다시 쓰지 않는다.
 */
public class ListingRequiredAgreementMissingException extends BusinessException {

  public ListingRequiredAgreementMissingException() {
    super(ErrorCode.LISTING_REQUIRED_AGREEMENT_MISSING);
  }
}
