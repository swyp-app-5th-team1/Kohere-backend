package com.kohere.listing.application.dto;

import com.kohere.listing.domain.Listing;
import com.kohere.listing.domain.ListingType;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/** 내 찜 목록 응답 DTO. public listing 문서 구조에 찜한 시각을 더한다. */
public record FavoriteListingResponse(
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
    List<ListingDetailResponse.RoomOfferResponse> roomOffers,
    Listing.Descriptions descriptions,
    List<String> imageUrls,
    boolean favorited,
    int favoriteCount,
    Instant createdAt,
    Instant updatedAt,
    Instant favoritedAt) {}
