package com.kohere.listing.domain;

import com.kohere.common.exception.BusinessException;
import com.kohere.common.exception.ErrorCode;

/** 지도 조회 범위 좌표가 빠졌거나 서로 맞지 않을 때 쓰는 예외다. */
public class ListingInvalidBboxException extends BusinessException {

  /** 전역 예외 핸들러가 400 LISTING_INVALID_BBOX 응답으로 바꾼다. */
  public ListingInvalidBboxException() {
    super(ErrorCode.LISTING_INVALID_BBOX);
  }
}
