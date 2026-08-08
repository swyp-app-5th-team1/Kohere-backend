package com.kohere.listing.application.dto;

import com.kohere.listing.domain.place.SearchPlaceType;

/**
 * 키워드가 매칭된 장소 정보다.
 *
 * <p>이 값이 {@code null}이면 검색어가 POI 사전에 없다는 뜻이다. 프론트는 이 경우 "검색된 장소가 없어요" 같은 빈 상태 문구를 보여주면 된다.
 */
public record MatchedPlaceResponse(SearchPlaceType type, String name, double lat, double lng) {}
