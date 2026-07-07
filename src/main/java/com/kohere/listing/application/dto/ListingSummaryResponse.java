package com.kohere.listing.application.dto;

import com.kohere.listing.domain.ConditionTag;
import com.kohere.listing.domain.Listing;
import com.kohere.listing.domain.ListingType;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * 매물 목록·키워드 검색 항목 응답 DTO. MongoDB v2 저장 구조에 가깝게 매물 필드를 노출한다.
 *
 * @param conditions 카드 조건 배지에 사용할 매물 단위 조건 목록. ACTIVE 방 상품들의 {@code filterTags} 합집합에 매물 정책 파생 조건을
 *     더한 값이다.
 */
public record ListingSummaryResponse(
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
    Integer distanceMeters,
    boolean favorited,
    int favoriteCount,
    Instant createdAt,
    Instant updatedAt) {}
