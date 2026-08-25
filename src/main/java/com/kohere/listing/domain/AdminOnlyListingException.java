package com.kohere.listing.domain;

import com.kohere.common.exception.BusinessException;
import com.kohere.common.exception.ErrorCode;

/**
 * 관리자가 아닌 회원이 매물 심사 API를 호출했을 때 던진다.
 *
 * <p>보안 필터는 {@code hasRole("USER")}로 정식 회원인지만 본다. 관리자 여부는 {@code user::api}의 {@code userType}으로
 * 서비스가 다시 확인한다 — {@link LandlordOnlyListingException}과 같은 이중 인가이며, 토큰에 관리자 여부를 담지 않으므로 <b>권한 회수가 즉시
 * 반영</b>된다.
 */
public class AdminOnlyListingException extends BusinessException {

  public AdminOnlyListingException() {
    super(ErrorCode.FORBIDDEN);
  }
}
