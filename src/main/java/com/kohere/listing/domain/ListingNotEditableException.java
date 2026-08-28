package com.kohere.listing.domain;

import com.kohere.common.exception.BusinessException;
import com.kohere.common.exception.ErrorCode;

/**
 * 심사가 진행 중이라 임대인이 매물을 수정할 수 없을 때 던진다({@code PENDING}·{@code UPDATE_PENDING}).
 *
 * <p>값이 잘못된 것이 아니라 <b>지금은 때가 아니라는 의미상 거부</b>라 400이 아니라 422다 — 관리자가 심사를 마치면 같은 요청이 그대로 성공한다. 이 저장소는
 * "나중에는 성공하는 선행조건 위반"을 일관되게 422로 쓴다({@code AUTH_PHONE_NOT_VERIFIED}도 같은 성격이다).
 *
 * <p>재조회 후 재시도로 풀리는 {@link ListingStateChangedException}(409)과 구분한다 — 이쪽은 <b>기다려야</b> 하고 저쪽은 <b>다시
 * 시도</b>하면 된다. 클라이언트의 행동이 다르므로 코드를 나눈다.
 */
public class ListingNotEditableException extends BusinessException {

  public ListingNotEditableException() {
    super(ErrorCode.LISTING_NOT_EDITABLE);
  }
}
