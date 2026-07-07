package com.kohere.listing.application.dto;

import com.kohere.listing.domain.ConditionTag;
import com.kohere.listing.domain.Listing;
import com.kohere.listing.domain.ListingType;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * 최근 본 매물 응답 DTO. public listing 문서 구조에 마지막 조회 시각을 더한다.
 *
 * @param conditions 최근 본 카드 조건 배지에 사용할 매물 단위 조건 목록. ACTIVE 방 상품들의 {@code filterTags} 합집합에 매물 정책 파생
 *     조건을 더한 값이다.
 */
public record RecentListingResponse(
    String listingId,
    String title,
    ListingType type,
    Listing.ListingStatus status,
    Listing.RentalType rentalType,
    Listing.RefundPolicy refundPolicy,
    Listing.Contract contract,
    Listing.GenderPolicy genderPolicy,
    ListingDetailResponse.GeoPoint location,
    Listing.Address address,
    Listing.NearestTransit nearestTransit,
    Set<String> nearbyUniversityCodes,
    Listing.Building building,
    Listing.PropertyPolicies propertyPolicies,
    Listing.Facilities facilities,
    Set<ConditionTag> conditions,
    List<ListingDetailResponse.RoomOfferResponse> roomOffers,
    Listing.Descriptions descriptions,
    List<String> imageUrls,
    boolean favorited,
    int favoriteCount,
    Instant createdAt,
    Instant updatedAt,
    Instant viewedAt) {}
