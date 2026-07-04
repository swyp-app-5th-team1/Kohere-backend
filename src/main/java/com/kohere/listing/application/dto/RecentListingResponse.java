package com.kohere.listing.application.dto;

import com.kohere.listing.domain.ConditionTag;
import com.kohere.listing.domain.ListingType;
import java.time.Instant;
import java.util.List;

/**
 * 최근 본 매물 목록의 카드 응답 DTO.
 *
 * <p>프론트가 일반 매물 리스트 카드와 거의 같은 컴포넌트로 렌더링할 수 있도록 {@link ListingSummaryResponse}와 동일한 가격 범위·주소·좌표·조건
 * 필드를 유지한다. 최근 본 화면에서만 필요한 마지막 조회 시각 {@code viewedAt}을 추가로 내려주며, 하트는 현재 로그인 사용자의 실제 찜 여부를 담는다.
 */
public record RecentListingResponse(
    String listingId,
    String title,
    ListingType type,
    int minMonthlyRent,
    int maxMonthlyRent,
    int minDeposit,
    int maxDeposit,
    int minMaintenanceFee,
    int maxMaintenanceFee,
    int minStayMonths,
    int maxStayMonths,
    String thumbnailUrl,
    double lat,
    double lng,
    String address,
    ListingSummaryResponse.NearestTransitSummary nearestTransit,
    List<ConditionTag> conditions,
    Integer distanceMeters,
    boolean favorited,
    int favoriteCount,
    Instant viewedAt) {}
