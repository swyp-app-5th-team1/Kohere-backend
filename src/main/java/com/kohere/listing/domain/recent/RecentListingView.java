package com.kohere.listing.domain.recent;

import com.kohere.listing.domain.Listing;

/**
 * 최근 본 매물 목록 한 행을 만들기 위한 도메인 조회 결과.
 *
 * <p>{@link RecentListing}에는 정렬 기준인 {@code viewedAt}과 식별자만 있고, 카드 화면에 필요한 제목·가격·주소·조건은 {@link
 * Listing}에 있다. 저장소가 MongoDB에서 두 컬렉션을 함께 조회한 뒤 응용 계층이 프론트 응답 DTO로 변환할 수 있도록 둘을 한 묶음으로 전달한다.
 */
public record RecentListingView(RecentListing recentListing, Listing listing) {}
