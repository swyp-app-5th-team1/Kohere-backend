package com.kohere.listing.application.dto;

import com.kohere.listing.domain.ConditionTag;
import com.kohere.listing.domain.ListingType;
import java.util.List;

/**
 * 매물 요약 응답 DTO. 응용 계층이 도메인을 표현 계층으로 전달할 때 쓰는 결과 타입이다(표현 계층은 이를 공통 래퍼로 감싼다).
 *
 * <p>docs/api/specs/03-listings-favorites.md 리스트 항목 스키마.
 */
public record ListingSummaryResponse(
    String listingId,
    String title,
    ListingType type,
    int monthlyRent,
    int deposit,
    String thumbnailUrl,
    double lat,
    double lng,
    List<ConditionTag> conditions,
    boolean favorited,
    int favoriteCount) {}
