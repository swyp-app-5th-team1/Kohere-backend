package com.kohere.listing.domain;

import java.util.Objects;

/**
 * 매물 목록 조회에서 화면 카드 1개를 만들기 위한 도메인 조회 결과다.
 *
 * <p>{@link Listing}은 고시원·쉐어하우스 같은 건물/숙소 단위이고, {@link Listing.RoomOffer}는 그 안에 있는 같은 가격·조건·재고의 방
 * 묶음이다. 목록 화면은 이제 건물 하나가 아니라 "건물 + 방 상품" 조합을 카드 1개로 보여주므로, 이 타입으로 두 값을 항상 함께 넘긴다.
 *
 * @param listing 방 상품이 속한 건물/숙소 매물
 * @param roomOffer 목록 카드에 표시할 방 상품 묶음
 */
public record ListingSearchResult(Listing listing, Listing.RoomOffer roomOffer) {

  /** 목록 카드에는 건물 정보와 방 상품 정보가 모두 필요하므로 둘 중 하나라도 없으면 잘못된 조회 결과다. */
  public ListingSearchResult {
    Objects.requireNonNull(listing, "listing은 필수입니다.");
    Objects.requireNonNull(roomOffer, "roomOffer는 필수입니다.");
  }
}
