package com.kohere.listing.application.dto;

import com.kohere.listing.domain.ConditionTag;
import com.kohere.listing.domain.ListingType;
import java.time.Instant;
import java.util.List;

/**
 * 내 찜 목록 전용 매물 요약 응답.
 *
 * <p>일반 매물 목록의 카드 정보와 거의 같지만, 찜 목록 화면은 "언제 찜했는지"를 기준으로 최신순 정렬하고 그 시각을 화면/디버깅에서 사용할 수 있어야 하므로 {@code
 * favoritedAt}을 함께 내려준다. 이 DTO의 모든 항목은 이미 현재 사용자가 찜한 매물이므로 {@code favorited}는 항상 {@code true}다.
 */
public record FavoriteListingResponse(
    String listingId,
    String title,
    ListingType type,
    int monthlyRent,
    int deposit,
    int maintenanceFee,
    String thumbnailUrl,
    double lat,
    double lng,
    String address,
    List<ConditionTag> conditions,
    boolean favorited,
    int favoriteCount,
    Instant favoritedAt) {}
