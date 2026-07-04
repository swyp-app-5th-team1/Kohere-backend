package com.kohere.listing.application.dto;

import java.util.List;

/**
 * 최근 본 매물 목록 응답.
 *
 * <p>최근 본 매물은 사용자가 별도 페이지를 넘기며 탐색하는 목록이 아니라 "내가 마지막으로 본 매물 최대 10개" 고정 목록이다. 그래서 page 객체 없이 {@code
 * content} 배열만 내려준다.
 */
public record RecentListingsResponse(List<RecentListingResponse> content) {}
