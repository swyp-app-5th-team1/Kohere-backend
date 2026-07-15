package com.kohere.listing.application.dto;

import java.util.List;

/**
 * 네이버 장소 후보 검색 응답이다.
 *
 * <p>검색 결과는 네이버가 반환한 순서를 유지하며 최대 5개다. 결과가 없으면 {@code items=[]}를 반환해 정상적인 빈 검색과 외부 장애를 구분한다.
 *
 * @param items 프론트 검색 결과 목록에 표시할 장소 후보
 */
public record ListingPlaceSearchResponse(List<Item> items) {

  /**
   * 프론트가 장소명·주소를 표시하고 지도 카메라 중심을 이동하는 데 필요한 최소 필드다.
   *
   * @param title 네이버가 검색어를 {@code <b>}로 강조한 장소명 원문
   * @param address 지번 주소. 제공되지 않으면 빈 문자열
   * @param roadAddress 도로명 주소. 제공되지 않으면 빈 문자열
   * @param lat WGS84 십진수 위도
   * @param lng WGS84 십진수 경도
   */
  public record Item(String title, String address, String roadAddress, double lat, double lng) {}
}
