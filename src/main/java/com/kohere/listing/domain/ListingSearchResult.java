package com.kohere.listing.domain;

import java.util.List;
import java.util.Objects;

/**
 * 매물 목록 조회에서 화면 카드 1개를 만들기 위한 도메인 조회 결과다.
 *
 * <p>{@link Listing}은 고시원·쉐어하우스 같은 건물/숙소 단위이고, {@link Listing.RoomOffer}는 그 안에 있는 같은 가격·계약·조건·재고의 방
 * 묶음이다. 목록과 키워드 검색 화면은 건물/숙소 매물 1개를 카드 1개로 보여주되, 가격·보증금·관리비·계약기간·재고는 조건을 통과한 방 상품들을 집계해서 표시한다. 그래서
 * 이 타입은 매물 1개와 그 매물 안에서 실제로 검색 조건을 만족한 방 상품 목록을 함께 넘긴다.
 *
 * @param listing 화면 카드 1개가 나타내는 건물/숙소 매물
 * @param roomOffers 목록 카드의 범위 값 계산에 사용할, 검색 조건을 통과한 활성 방 상품 목록
 */
public record ListingSearchResult(Listing listing, List<Listing.RoomOffer> roomOffers) {

  /** 목록 카드는 매물 정보와 최소 1개 이상의 매칭 방 상품이 함께 있어야 만들 수 있다. */
  public ListingSearchResult {
    Objects.requireNonNull(listing, "listing은 필수입니다.");
    Objects.requireNonNull(roomOffers, "roomOffers는 필수입니다.");
    if (roomOffers.isEmpty()) {
      throw new IllegalArgumentException("목록 응답에는 조건을 만족한 방 상품이 최소 1개 필요합니다.");
    }
    roomOffers = List.copyOf(roomOffers);
  }
}
