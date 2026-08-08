package com.kohere.listing.domain.place;

/**
 * 외부 장소 검색 결과를 listing 모듈 내부에서 사용하는 제공자 독립 값으로 표현한다.
 *
 * <p>네이버 원본의 {@code mapx}/{@code mapy}나 응답 메타데이터를 도메인 밖으로 전달하지 않고, 지도 SDK와 기존 listing API가 사용하는
 * WGS84 {@code lat}/{@code lng}만 보존한다. {@code title}의 검색어 강조 태그는 프론트 표시 계약에 따라 그대로 유지한다.
 */
public record PlaceSearchResult(
    String title, String address, String roadAddress, double lat, double lng) {}
