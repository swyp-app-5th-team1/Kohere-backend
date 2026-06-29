package com.kohere.listing.application.dto;

import java.util.List;

/** 지도 SDK가 개별 마커로 사용할 매물 좌표 목록 응답이다. */
public record ListingMapResponse(List<Marker> markers, long total) {

  /** 지도에 찍을 매물 하나의 식별자와 좌표다. */
  public record Marker(String listingId, double lat, double lng) {}
}
