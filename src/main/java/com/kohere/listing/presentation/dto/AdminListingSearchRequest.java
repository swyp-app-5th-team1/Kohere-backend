package com.kohere.listing.presentation.dto;

import com.kohere.listing.domain.Listing;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.Collections;
import java.util.Set;

/**
 * 관리자 매물 심사 목록 요청이다({@code GET /api/v1/admin/listings}).
 *
 * <p>{@code status}는 <b>생략하면 전체</b>다. 상태별 조회를 별도 엔드포인트로 나누지 않고 이 파라미터로 흡수한다 — 경로를 나누면 페이지네이션·정렬 계약을
 * 두 벌 문서화·테스트해야 하고 "전체"가 {@code status} 생략과 중복된다.
 *
 * @param status 조회할 상태. 콤마로 여러 개 보낼 수 있다({@code ?status=PENDING,REJECTED})
 * @param sort 정렬 키. {@code createdAt,asc}(등록 오래된 순)와 {@code updatedAt,desc}(최근 수정순 — 수정 재심사 건이 등록
 *     최신순 큐 바닥에 가라앉는 것을 따로 본다)를 인식하고, <b>그 밖의 값이나 생략은 400이 아니라 등록 최신순</b>이다. 허용값의 정본은 {@code
 *     ListingRepositoryImpl#adminSort}이며, 모르는 키 하나로 심사 목록이 멈추지 않게 조용히 기본값으로 간다
 */
public record AdminListingSearchRequest(
    Set<Listing.ListingStatus> status,
    @Min(0) Integer page,
    @Min(1) @Max(100) Integer size,
    String sort) {

  private static final int DEFAULT_PAGE = 0;
  private static final int DEFAULT_SIZE = 20;

  /**
   * 생략된 값을 기본값으로 채운다.
   *
   * <p>{@code page}·{@code size}를 원시 {@code int}가 아니라 박스 타입으로 받는 이유는 <b>파라미터 부재와 0을 구분</b>하기 위해서다.
   * 원시 타입이면 쿼리에 값이 없을 때 바인딩 자체가 실패해 {@code 400}이 나므로, 아무 조건 없이 전체를 조회하는 가장 기본적인 호출이 막힌다.
   */
  public AdminListingSearchRequest {
    status = status == null ? Collections.emptySet() : Set.copyOf(status);
    page = page == null ? DEFAULT_PAGE : page;
    size = size == null ? DEFAULT_SIZE : size;
  }
}
