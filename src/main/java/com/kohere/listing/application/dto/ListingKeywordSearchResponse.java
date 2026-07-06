package com.kohere.listing.application.dto;

import com.kohere.common.response.PageInfo;
import java.util.List;

/**
 * 키워드 검색 응답이다.
 *
 * <p>{@code matchedPlace}는 검색어로 찾은 장소이고, {@code content}는 그 장소 주변의 매물/건물 단위 목록이다. 각 항목은 public
 * listing 문서 구조를 따르며, {@code roomOffers[]}에는 조건을 통과한 방 상품만 담는다. 장소를 찾지 못하면 {@code
 * matchedPlace=null}, {@code content=[]}, {@code page.totalElements=0}으로 200 OK를 반환한다.
 */
public record ListingKeywordSearchResponse(
    MatchedPlaceResponse matchedPlace, List<ListingSummaryResponse> content, PageInfo page) {}
