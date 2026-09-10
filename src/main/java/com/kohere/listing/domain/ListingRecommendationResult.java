package com.kohere.listing.domain;

import java.util.List;
import java.util.Objects;

/**
 * 진단 추천 카드 1개를 만들기 위한 조회 결과다. 매물과 <b>그 매물 안에서 진단 조건을 실제로 만족한 방 상품</b>을 함께 넘긴다.
 *
 * <p>{@link ListingSearchResult}와 달리 <b>빈 목록을 거부하지 않는다.</b> 목록 조회는 매칭 방이 0개인 매물을 카드에서 떨어뜨릴 수 있지만,
 * 추천 조회는 매물을 떨어뜨릴 수 없다 — 페이지 메타가 저장소 count에서 나오므로 하나라도 빠지면 {@code totalElements}가 거짓이 된다. 대신 빈 경우의
 * 정책(전체 활성 방으로 폴백)을 저장소가 정한다.
 *
 * @param listing 카드 1개가 나타내는 매물
 * @param roomOffers 카드의 가격 범위·조건 배지·가격순 정렬에 사용할, 진단 조건을 통과한 방 상품 목록
 */
public record ListingRecommendationResult(Listing listing, List<Listing.RoomOffer> roomOffers)
    implements MatchedRoomOffers {

  public ListingRecommendationResult {
    Objects.requireNonNull(listing, "listing은 필수입니다.");
    roomOffers = List.copyOf(Objects.requireNonNull(roomOffers, "roomOffers는 필수입니다."));
  }
}
