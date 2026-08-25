package com.kohere.listing.domain;

import com.kohere.common.exception.InvalidInputException;
import java.util.Collection;
import java.util.List;

/**
 * 저장 전 애플리케이션 경로에서 매물 문서의 필수 구조와 도메인 불변식을 검증한다.
 *
 * <p>MongoDB는 스키마리스라 잘못된 모양의 문서도 저장 자체는 가능하다. 그래서 애플리케이션이 저장하기 직전에 v4 스키마의 필수 필드와 값 범위를 검증해, 조회 시점의
 * NullPointerException이나 잘못된 검색 결과를 미리 막는다.
 *
 * <p><b>「요청에서 선택」과 「저장에서 선택」은 다르다.</b> {@code preferredNationalities}·{@code contractDifficulties}는
 * 등록·수정 요청에서 생략할 수 있지만(#270) 저장 계약은 그대로다 — 응용 계층이 빈 집합으로 접어 넘기므로 여기서는 계속 non-null을 요구한다. 이 두 줄을
 * 「요청이 선택이 됐으니」라는 이유로 지우면 접기를 빠뜨린 경로가 생겼을 때 아무도 못 잡는다(테스트 프로파일은 {@code mongock.enabled: false}라
 * MongoDB validator가 걸리지 않는다).
 *
 * <p>{@code blogUrl}·{@code rejectionReason}·{@code serviceFeedback}은 값이 없을 수 있어 필수로 보지 않는다.
 * MongoDB validator의 {@code required} 목록도 같은 셋을 제외한다. {@code location}은 지오코딩이 없던 시절에 같은 예외였지만, 등록이
 * 주소 검색이 준 좌표를 받게 되면서 필수로 조였다(ADR-0042 · changeUnit {@code 0116}).
 */
public final class ListingValidator {

  private ListingValidator() {}

  public static void validateForSave(Listing listing) {
    requireNonNull(listing, "listing이 필요합니다.");
    require(listing.getSchemaVersion() == 4, "schemaVersion은 4여야 합니다.");
    requireNonNull(listing.getLandlordId(), "landlordId가 필요합니다.");
    validateContact(listing.getContact());
    requireText(listing.getBusinessRegistrationNumber(), "businessRegistrationNumber가 필요합니다.");
    validateAgeRange(listing.getAgeMin(), listing.getAgeMax());
    requireLocalizedText(listing.getTitle(), "title");
    requireNonNull(listing.getType(), "type이 필요합니다.");
    requireNonNull(listing.getRentalType(), "rentalType이 필요합니다.");
    requireNonNull(listing.getStatus(), "status가 필요합니다.");
    requireNonNull(listing.getGenderPolicy(), "genderPolicy가 필요합니다.");
    requireCollection(listing.getLanguagesSupported(), "languagesSupported");
    require(listing.getFavoriteCount() >= 0, "favoriteCount는 0 이상이어야 합니다.");
    requireCollection(listing.getImageUrls(), "imageUrls");
    requireCollection(listing.getNearbyUniversityCodes(), "nearbyUniversityCodes");
    requireNonNull(listing.getCreatedAt(), "createdAt이 필요합니다.");
    requireNonNull(listing.getUpdatedAt(), "updatedAt이 필요합니다.");
    validateAddress(listing.getAddress());
    requireNonNull(listing.getLocation(), "location이 필요합니다.");
    validateBuilding(listing.getBuilding());
    requireLocalizedText(listing.getDescription(), "description");
    requireLocalizedText(listing.getExtraNotes(), "extraNotes");
    validateFacilities(listing.getFacilities());
    validateNearestTransit(listing.getNearestTransit());
    requireCollection(listing.getNearbyFacilities(), "nearbyFacilities");
    requireNonNull(listing.getArcRequired(), "arcRequired가 필요합니다.");
    requireLocalizedText(listing.getRefundPolicy(), "refundPolicy");
    validateRoomOffers(listing.getRoomOffers());
    requireCollection(listing.getPreferredNationalities(), "preferredNationalities");
    requireCollection(listing.getContractDifficulties(), "contractDifficulties");
    validateConsents(listing.getConsents());
  }

  /**
   * 등록 시 받은 이용약관 동의가 저장 계약을 만족하는지 본다.
   *
   * <p>MongoDB validator가 {@code consents}를 {@code required}로 걸어 두었는데 <b>여기서는 보지 않고 있었다</b> — 매퍼가
   * {@code null}을 조용히 통과시키고 테스트 프로파일은 {@code mongock.enabled: false}라 validator가 걸리지 않아, 값을 승계하지 못한
   * 저장 경로가 생기면 <b>테스트는 통과하고 dev에서 {@code MongoWriteException}이 나는</b> 조합이 된다. 수정 경로가 동의를 승계하므로(요청은
   * 다시 받되 저장 값은 유지) 앱 계층에서 먼저 막는다.
   */
  private static void validateConsents(Listing.Consents consents) {
    requireNonNull(consents, "consents가 필요합니다.");
    requireText(consents.version(), "consents.version이 필요합니다.");
    requireNonNull(consents.agreedAt(), "consents.agreedAt이 필요합니다.");
  }

  private static void validateContact(Listing.Contact contact) {
    requireNonNull(contact, "contact가 필요합니다.");
    requireText(contact.managerName(), "contact.managerName이 필요합니다.");
    // phone은 지점 대표 전화 하나뿐이다. 문자문의 번호(contact.sms)는 임대인 개인 번호를 매물 문서로 복사하는 통로라
    // 필드째 제거했다(ADR-0039 Amended · changeUnit 0119).
    requireText(contact.phone(), "contact.phone이 필요합니다.");
  }

  private static void validateAgeRange(int ageMin, int ageMax) {
    require(ageMin >= 0, "ageMin은 0 이상이어야 합니다.");
    require(ageMin <= ageMax, "ageMin은 ageMax 이하여야 합니다.");
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
  }

  private static void validateRoomOffers(List<Listing.RoomOffer> roomOffers) {
    requireCollection(roomOffers, "roomOffers");
    require(!roomOffers.isEmpty(), "roomOffers는 1개 이상이어야 합니다.");
    // 임대인 수정이 방을 INACTIVE로 내릴 수 있게 되면서 전부 내린 매물이 저장될 여지가 생겼다.
    // 그대로 두면 상태만 PUBLISHED이고 목록·상세에는 나타나지 않는 매물이 만들어진다 —
    // 조회 3종과 requirePublishedListing이 모두 ACTIVE 방 상품을 요구하기 때문이다.
    require(
        roomOffers.stream().anyMatch(offer -> offer.status() == Listing.RoomOfferStatus.ACTIVE),
        "roomOffers에는 활성 상태인 방이 1개 이상 있어야 합니다.");
    roomOffers.forEach(ListingValidator::validateRoomOffer);
  }

  /**
   * 방 상품 하나의 필수 구조를 검증한다.
   *
   * <p>{@code roomOfferId}는 매물 {@code id}와 마찬가지로 값이 없으면 저장 어댑터가 ObjectId를 발급하므로 필수로 보지 않는다. 저장 시점의
   * 문서에는 항상 채워져 들어가므로 MongoDB validator의 {@code required}와도 어긋나지 않는다.
   */
  private static void validateRoomOffer(Listing.RoomOffer roomOffer) {
    requireNonNull(roomOffer, "roomOffers 항목이 필요합니다.");
    requireLocalizedText(roomOffer.name(), "roomOffers.name");
    requireNonNull(roomOffer.status(), "roomOffers.status가 필요합니다.");
    requireNonNull(roomOffer.contract(), "roomOffers.contract가 필요합니다.");
    validatePricing(roomOffer.pricing());
    requireCollection(roomOffer.filterTags(), "roomOffers.filterTags");
    requireCollection(roomOffer.roomImageUrls(), "roomOffers.roomImageUrls");
  }

  private static void validatePricing(Listing.Pricing pricing) {
    requireNonNull(pricing, "roomOffers.pricing이 필요합니다.");
    requireNonNull(pricing.currency(), "roomOffers.pricing.currency가 필요합니다.");
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
