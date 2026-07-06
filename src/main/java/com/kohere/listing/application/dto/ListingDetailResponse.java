package com.kohere.listing.application.dto;

import com.kohere.listing.domain.ConditionTag;
import com.kohere.listing.domain.Listing;
import com.kohere.listing.domain.ListingType;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/** 매물 상세 응답 DTO. MongoDB v2 저장 구조에 가깝게 매물 필드를 노출한다. */
public record ListingDetailResponse(
    String listingId,
    String title,
    ListingType type,
    Listing.ListingStatus status,
    Listing.RentalType rentalType,
    Listing.RefundPolicy refundPolicy,
    Listing.Contract contract,
    Listing.GenderPolicy genderPolicy,
    GeoPoint location,
    Listing.Address address,
    Listing.NearestTransit nearestTransit,
    Set<String> nearbyUniversityCodes,
    Listing.Building building,
    Listing.PropertyPolicies propertyPolicies,
    Listing.Facilities facilities,
    List<RoomOfferResponse> roomOffers,
    Listing.Descriptions descriptions,
    List<String> imageUrls,
    boolean favorited,
    int favoriteCount,
    Instant createdAt,
    Instant updatedAt) {

  /** 프론트 지도 컴포넌트에서 바로 쓰기 쉬운 위도·경도 값이다. */
  public record GeoPoint(double lat, double lng) {}

  /** 동일 가격·조건을 공유하는 실제 방 묶음 하나다. 상세 응답에는 ACTIVE 방 상품만 포함한다. */
  public record RoomOfferResponse(
      String roomOfferId,
      String name,
      Listing.RoomOfferStatus status,
      Listing.Pricing pricing,
      Listing.Inventory inventory,
      Set<ConditionTag> filterTags,
      List<String> roomImageUrls) {}
}
