package com.kohere.listing.domain;

import java.util.List;

/** 지도 마커 조회 결과다. 조회된 매물 목록과 필터에 맞는 전체 매물 수를 함께 담는다. */
public record ListingMapSearchResult(List<Listing> listings, long total) {

  /** 외부에서 목록을 바꾸지 못하도록 복사해서 보관한다. */
  public ListingMapSearchResult {
    listings = List.copyOf(listings);
  }
}
