package com.kohere.listing.domain;

import com.kohere.common.exception.BusinessException;
import com.kohere.common.exception.ErrorCode;

/** 지도 마커 조회 결과가 서버 상한을 넘어 프론트가 더 좁은 범위로 재조회해야 할 때 쓰는 예외다. */
public class ListingAreaTooLargeException extends BusinessException {

  /** 전역 예외 핸들러가 400 LISTING_AREA_TOO_LARGE 응답으로 바꾼다. */
  public ListingAreaTooLargeException() {
    super(ErrorCode.LISTING_AREA_TOO_LARGE);
  }
}
