package com.kohere.listing.domain;

import java.util.List;

/**
 * 매물과 <b>그 안에서 조회 조건을 통과한 방 상품</b>의 쌍이다.
 *
 * <p>목록 카드({@link ListingSearchResult})와 추천 카드({@link ListingRecommendationResult})가 이 계약을 공유해 가격
 * 집계와 가격순 정렬이 <b>같은 방 목록</b>을 보게 만든다. 둘을 따로 두면 한쪽만 고쳐져 화면에 보이는 가격과 정렬 기준이 갈린다 — 실제로 그랬다.
 */
public interface MatchedRoomOffers {

  /** 카드 1개가 나타내는 매물. */
  Listing listing();

  /** 조회 조건을 통과한 방 상품. 가격 범위·조건 배지·가격순 정렬이 모두 이 목록만 본다. */
  List<Listing.RoomOffer> roomOffers();
}
