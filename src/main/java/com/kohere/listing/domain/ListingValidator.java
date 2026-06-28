package com.kohere.listing.domain;

import com.kohere.common.exception.InvalidInputException;
import java.util.Collection;
import java.util.List;

/** 저장 전 애플리케이션 경로에서 매물 문서의 필수 구조와 도메인 불변식을 검증한다. */
public final class ListingValidator {

  private ListingValidator() {}

  public static void validateForSave(Listing listing) {
    requireNonNull(listing, "listing이 필요합니다.");
    require(listing.getSchemaVersion() >= 1, "schemaVersion은 1 이상이어야 합니다.");
    requireNonNull(listing.getLandlordId(), "landlordId가 필요합니다.");
    requireText(listing.getTitle(), "title이 필요합니다.");
    requireNonNull(listing.getType(), "type이 필요합니다.");
    requireNonNull(listing.getStatus(), "status가 필요합니다.");
    requireNonNull(listing.getLocation(), "location이 필요합니다.");
    validateAddress(listing.getAddress());
    validateNearestTransit(listing.getNearestTransit());
    validateBuilding(listing.getBuilding());
    requireNonNull(listing.getPropertyPolicies(), "propertyPolicies가 필요합니다.");
    validateFacilities(listing.getFacilities());
    validateRoomOffers(listing.getRoomOffers());
    requireCollection(listing.getFeatureSummary(), "featureSummary");
    requireCollection(listing.getNearbyUniversityCodes(), "nearbyUniversityCodes");
    requireNonNull(listing.getDescriptions(), "descriptions가 필요합니다.");
    requireCollection(listing.getImageUrls(), "imageUrls");
    require(listing.getFavoriteCount() >= 0, "favoriteCount는 0 이상이어야 합니다.");
    requireNonNull(listing.getCreatedAt(), "createdAt이 필요합니다.");
    requireNonNull(listing.getUpdatedAt(), "updatedAt이 필요합니다.");
  }

  private static void validateAddress(Listing.Address address) {
    requireNonNull(address, "address가 필요합니다.");
    requireText(address.city(), "address.city가 필요합니다.");
    requireText(address.district(), "address.district가 필요합니다.");
    requireText(address.fullAddress(), "address.fullAddress가 필요합니다.");
  }

  private static void validateNearestTransit(Listing.NearestTransit transit) {
    requireNonNull(transit, "nearestTransit이 필요합니다.");
    requireNonNull(transit.type(), "nearestTransit.type이 필요합니다.");
    requireText(transit.name(), "nearestTransit.name이 필요합니다.");
  }

  private static void validateBuilding(Listing.Building building) {
    requireNonNull(building, "building이 필요합니다.");
    requireNonNull(building.type(), "building.type이 필요합니다.");
    require(building.usedFloorMin() <= building.usedFloorMax(), "building 사용 층 범위가 올바르지 않습니다.");
    require(building.totalFloors() >= 1, "building.totalFloors는 1 이상이어야 합니다.");
    require(building.usedFloorMax() <= building.totalFloors(), "building 사용 층은 전체 층수를 넘을 수 없습니다.");
    requireNonNull(building.heatingSystem(), "building.heatingSystem이 필요합니다.");
  }

  private static void validateFacilities(Listing.Facilities facilities) {
    requireNonNull(facilities, "facilities가 필요합니다.");
    requireCollection(facilities.laundry(), "facilities.laundry");
    requireCollection(facilities.livingAmenities(), "facilities.livingAmenities");
    requireCollection(facilities.securityFeatures(), "facilities.securityFeatures");
    requireCollection(facilities.commonSpaces(), "facilities.commonSpaces");
    requireCollection(facilities.providedSupplies(), "facilities.providedSupplies");
    facilities
        .commonSpaces()
        .forEach(
            space -> {
              requireNonNull(space.type(), "facilities.commonSpaces.type이 필요합니다.");
              if (space.count() != null) {
                require(space.count() >= 0, "facilities.commonSpaces.count는 0 이상이어야 합니다.");
              }
            });
  }

  private static void validateRoomOffers(List<Listing.RoomOffer> roomOffers) {
    requireCollection(roomOffers, "roomOffers");
    require(!roomOffers.isEmpty(), "roomOffers는 1개 이상이어야 합니다.");
    roomOffers.forEach(ListingValidator::validateRoomOffer);
  }

  private static void validateRoomOffer(Listing.RoomOffer roomOffer) {
    requireNonNull(roomOffer, "roomOffers 항목이 필요합니다.");
    requireText(roomOffer.name(), "roomOffers.name이 필요합니다.");
    requireNonNull(roomOffer.status(), "roomOffers.status가 필요합니다.");
    requireNonNull(roomOffer.rentalType(), "roomOffers.rentalType이 필요합니다.");
    validatePricing(roomOffer.pricing());
    validateContract(roomOffer.contract());
    requireNonNull(roomOffer.inventory(), "roomOffers.inventory가 필요합니다.");
    requireNonNull(roomOffer.genderPolicy(), "roomOffers.genderPolicy가 필요합니다.");
    requireCollection(roomOffer.features(), "roomOffers.features");
    requireCollection(roomOffer.filterTags(), "roomOffers.filterTags");
    requireCollection(roomOffer.roomImageUrls(), "roomOffers.roomImageUrls");
  }

  private static void validatePricing(Listing.Pricing pricing) {
    requireNonNull(pricing, "roomOffers.pricing이 필요합니다.");
    requireNonNull(pricing.currency(), "roomOffers.pricing.currency가 필요합니다.");
  }

  private static void validateContract(Listing.Contract contract) {
    requireNonNull(contract, "roomOffers.contract가 필요합니다.");
    requireNonNull(contract.refundPolicy(), "roomOffers.contract.refundPolicy가 필요합니다.");
    requireNonNull(contract.refundPolicy().code(), "roomOffers.contract.refundPolicy.code가 필요합니다.");
  }

  private static void requireCollection(Collection<?> values, String field) {
    requireNonNull(values, field + "가 필요합니다.");
    if (values.stream().anyMatch(value -> value == null)) {
      throw new InvalidInputException(field + "에는 null 항목을 담을 수 없습니다.");
    }
  }

  private static void requireText(String value, String message) {
    require(value != null && !value.isBlank(), message);
  }

  private static void requireNonNull(Object value, String message) {
    require(value != null, message);
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new InvalidInputException(message);
    }
  }
}
