package com.kohere.listing.domain;

import com.kohere.common.exception.BusinessException;
import com.kohere.common.exception.ErrorCode;

/**
 * 매물을 읽은 뒤 저장하기 전에 상태가 바뀌어 저장이 거절됐을 때 던진다.
 *
 * <p>매물 문서에는 낙관적 락 필드가 없고 저장이 문서 전체 교체라, 조건 없이 저장하면 나중에 쓴 쪽이 앞의 변경을 <b>소리 없이</b> 지운다. 임대인 수정 경로는
 * 읽기와 저장 사이에 사진 확정 복사가 끼어 그 창이 특히 넓으므로, 저장을 <b>읽은 시점의 상태를 조건으로 거는 교체</b>로 만들고 조건이 어긋나면 이 예외를 낸다.
 *
 * <p>{@link ListingNotEditableException}(422)과 달리 <b>재조회 후 재시도하면 성공할 수 있으므로</b> 409다.
 */
public class ListingStateChangedException extends BusinessException {

  public ListingStateChangedException() {
    super(ErrorCode.LISTING_STATE_CHANGED);
  }
}
