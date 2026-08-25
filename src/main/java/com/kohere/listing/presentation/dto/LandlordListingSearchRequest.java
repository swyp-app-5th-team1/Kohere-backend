package com.kohere.listing.presentation.dto;

import com.kohere.listing.domain.Listing;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.Collections;
import java.util.Set;

/**
 * 임대인 「내 매물」 목록 요청이다({@code GET /api/v2/users/me/listings}).
 *
 * <p>{@code status}는 <b>생략하면 전체</b>이며 관리자 심사 목록과 같은 콤마 구분 계약이다({@code ?status=REJECTED,PENDING}).
 *
 * <p><b>정렬 파라미터가 없다.</b> 최근 수정순 고정이다 — 열어 두면 세입자 조회의 {@code LISTING_INVALID_SORT_PARAM} 계약과 어긋나는
 * 규칙을 새로 만들게 되고, 나중에 여는 것은 하위 호환을 깨지 않는다. 상태로 좁힌 조회는 {@code listings_landlord_status_updated}가 정렬까지
 * 받는다.
 *
 * @param status 조회할 상태. 콤마로 여러 개 보낼 수 있다
 */
public record LandlordListingSearchRequest(
    Set<Listing.ListingStatus> status, @Min(0) Integer page, @Min(1) @Max(100) Integer size) {

  private static final int DEFAULT_PAGE = 0;
  private static final int DEFAULT_SIZE = 20;

  /**
   * 생략된 값을 기본값으로 채운다.
   *
   * <p>{@code page}·{@code size}를 박스 타입으로 받는 이유는 관리자 목록과 같다 — 원시 타입이면 쿼리에 값이 없을 때 바인딩이 실패해, 아무 조건
   * 없이 전체를 조회하는 가장 기본적인 호출이 막힌다.
   */
  public LandlordListingSearchRequest {
    status = status == null ? Collections.emptySet() : Set.copyOf(status);
    page = page == null ? DEFAULT_PAGE : page;
    size = size == null ? DEFAULT_SIZE : size;
  }
}
