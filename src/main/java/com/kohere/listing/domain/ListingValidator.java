package com.kohere.listing.domain;

import com.kohere.common.exception.InvalidInputException;
import java.util.Collection;
import java.util.List;

/**
 * 저장 전 애플리케이션 경로에서 매물 문서의 필수 구조와 도메인 불변식을 검증한다.
 *
 * <p>MongoDB는 스키마리스라 잘못된 모양의 문서도 저장 자체는 가능하다. 그래서 애플리케이션이 저장하기 직전에 v3 스키마의 필수 필드와 값 범위를 검증해, 조회 시점의
 * NullPointerException이나 잘못된 검색 결과를 미리 막는다.
 */
public final class ListingValidator {

  private ListingValidator() {}

  public static void validateForSave(Listing listing) {
    requireNonNull(listing, "listing이 필요합니다.");
    require(listing.getSchemaVersion() == 3, "schemaVersion은 3이어야 합니다.");
    requireNonNull(listing.getLandlordId(), "landlordId가 필요합니다.");
    requireLocalizedText(listing.getTitle(), "title");
    requireNonNull(listing.getType(), "type이 필요합니다.");
    requireNonNull(listing.getStatus(), "status가 필요합니다.");
    requireNonNull(listing.getRentalType(), "rentalType이 필요합니다.");
    validateRefundPolicy(listing.getRefundPolicy());
    validateContract(listing.getContract());
    requireNonNull(listing.getGenderPolicy(), "genderPolicy가 필요합니다.");
    requireNonNull(listing.getLocation(), "location이 필요합니다.");
    validateAddress(listing.getAddress());
    validateNearestTransit(listing.getNearestTransit());
    validateBuilding(listing.getBuilding());
    requireNonNull(listing.getPropertyPolicies(), "propertyPolicies가 필요합니다.");
    validateFacilities(listing.getFacilities());
    validateRoomOffers(listing.getRoomOffers());
    requireCollection(listing.getNearbyUniversityCodes(), "nearbyUniversityCodes");
    validateDescriptions(listing.getDescriptions());
    requireCollection(listing.getImageUrls(), "imageUrls");
    require(listing.getFavoriteCount() >= 0, "favoriteCount는 0 이상이어야 합니다.");
    requireNonNull(listing.getCreatedAt(), "createdAt이 필요합니다.");
    requireNonNull(listing.getUpdatedAt(), "updatedAt이 필요합니다.");
  }

  private static void validateAddress(Listing.Address address) {
    requireNonNull(address, "address가 필요합니다.");
    requireText(address.city(), "address.city가 필요합니다.");
    requireText(address.district(), "address.district가 필요합니다.");
    requireLocalizedText(address.fullAddress(), "address.fullAddress");
    if (address.detail() != null) {
      requireLocalizedText(address.detail(), "address.detail");
    }
  }

  private static void validateNearestTransit(Listing.NearestTransit transit) {
    requireNonNull(transit, "nearestTransit이 필요합니다.");
    requireNonNull(transit.type(), "nearestTransit.type이 필요합니다.");
    requireLocalizedText(transit.name(), "nearestTransit.name");
    requireLocalizedText(
        transit.nearbyPlacesDescription(), "nearestTransit.nearbyPlacesDescription");
  }

  private static void validateBuilding(Listing.Building building) {
    requireNonNull(building, "building이 필요합니다.");
    requireNonNull(building.type(), "building.type이 필요합니다.");
    require(building.usedFloorMin() <= building.usedFloorMax(), "building 사용 층 범위가 올바르지 않습니다.");
    require(building.totalFloors() >= 1, "building.totalFloors는 1 이상이어야 합니다.");
    require(building.usedFloorMax() <= building.totalFloors(), "building 사용 층은 전체 층수를 넘을 수 없습니다.");
  }

  private static void validateFacilities(Listing.Facilities facilities) {
    requireNonNull(facilities, "facilities가 필요합니다.");
    requireCollection(facilities.heatingSystem(), "facilities.heatingSystem");
    requireCollection(facilities.kitchen(), "facilities.kitchen");
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
    requireLocalizedText(roomOffer.name(), "roomOffers.name");
    requireNonNull(roomOffer.status(), "roomOffers.status가 필요합니다.");
    validatePricing(roomOffer.pricing());
    requireNonNull(roomOffer.inventory(), "roomOffers.inventory가 필요합니다.");
    requireCollection(roomOffer.filterTags(), "roomOffers.filterTags");
    validateStoredConditionTags(roomOffer.filterTags(), "roomOffers.filterTags");
    requireCollection(roomOffer.roomImageUrls(), "roomOffers.roomImageUrls");
  }

  private static void validateStoredConditionTags(Collection<ConditionTag> tags, String field) {
    require(!tags.contains(ConditionTag.NO_ARC), field + "에는 검색용 NO_ARC 태그를 저장할 수 없습니다.");
  }

  private static void validatePricing(Listing.Pricing pricing) {
    requireNonNull(pricing, "roomOffers.pricing이 필요합니다.");
    requireNonNull(pricing.currency(), "roomOffers.pricing.currency가 필요합니다.");
  }

  private static void validateContract(Listing.Contract contract) {
    requireNonNull(contract, "contract가 필요합니다.");
  }

  private static void validateRefundPolicy(Listing.RefundPolicy refundPolicy) {
    requireNonNull(refundPolicy, "refundPolicy가 필요합니다.");
    requireNonNull(refundPolicy.code(), "refundPolicy.code가 필요합니다.");
    requireLocalizedText(refundPolicy.description(), "refundPolicy.description");
  }

  private static void validateDescriptions(Listing.Descriptions descriptions) {
    requireNonNull(descriptions, "descriptions가 필요합니다.");
    requireText(descriptions.ko(), "descriptions.ko가 필요합니다.");
    requireText(descriptions.en(), "descriptions.en이 필요합니다.");
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

  /** 다국어 표시 필드에 한국어와 영어가 모두 들어 있는지 검증한다. */
  private static void requireLocalizedText(LocalizedText value, String field) {
    requireNonNull(value, field + "가 필요합니다.");
    requireText(value.ko(), field + ".ko가 필요합니다.");
    requireText(value.en(), field + ".en이 필요합니다.");
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
