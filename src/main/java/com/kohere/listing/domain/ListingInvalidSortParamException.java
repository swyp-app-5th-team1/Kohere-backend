package com.kohere.listing.domain;

import com.kohere.common.exception.BusinessException;
import com.kohere.common.exception.ErrorCode;

/** 거리순 정렬에 필요한 기준 좌표가 없을 때 쓰는 예외다. */
public class ListingInvalidSortParamException extends BusinessException {

  /** 전역 예외 핸들러가 400 LISTING_INVALID_SORT_PARAM 응답으로 바꾼다. */
  public ListingInvalidSortParamException() {
    super(ErrorCode.LISTING_INVALID_SORT_PARAM);
  }
}
