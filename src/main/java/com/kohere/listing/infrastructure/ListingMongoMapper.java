package com.kohere.listing.infrastructure;

import com.kohere.common.exception.InvalidInputException;
import com.kohere.listing.domain.Listing;
import java.util.List;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;

/** 순수 도메인 모델과 MongoDB 저장 모델의 변환을 한곳에서 담당한다. */
final class ListingMongoMapper {

  private ListingMongoMapper() {}

  /** MongoDB 문서를 비즈니스 로직에서 쓰는 순수 도메인 모델로 변환한다. */
  static Listing toDomain(ListingDocument document) {
    return Listing.builder()
        .id(document.getId().toHexString())
        .schemaVersion(document.getSchemaVersion())
        .landlordId(document.getLandlordId())
        .title(document.getTitle())
        .type(document.getType())
        .status(document.getStatus())
        .location(toDomain(document.getLocation()))
        .address(toDomain(document.getAddress()))
        .nearestTransit(toDomain(document.getNearestTransit()))
        .nearbyPlacesDescription(document.getNearbyPlacesDescription())
        .nearbyUniversityCodes(document.getNearbyUniversityCodes())
        .building(toDomain(document.getBuilding()))
        .propertyPolicies(toDomain(document.getPropertyPolicies()))
        .facilities(toDomain(document.getFacilities()))
        .roomOffers(document.getRoomOffers().stream().map(ListingMongoMapper::toDomain).toList())
        .featureSummary(document.getFeatureSummary())
        .descriptions(toDomain(document.getDescriptions()))
        .extraNotes(document.getExtraNotes())
        .imageUrls(document.getImageUrls())
        .favoriteCount(document.getFavoriteCount())
        .createdAt(document.getCreatedAt())
        .updatedAt(document.getUpdatedAt())
        .build();
  }

  /** 도메인 모델을 MongoDB 저장 전용 문서 구조로 변환한다. */
  static ListingDocument toDocument(Listing listing) {
    ObjectId id = objectIdOrNew(listing.getId(), "listingId");
    return ListingDocument.builder()
        .id(id)
        .schemaVersion(listing.getSchemaVersion())
        .landlordId(listing.getLandlordId())
        .title(listing.getTitle())
        .type(listing.getType())
        .status(listing.getStatus())
        .location(toDocument(listing.getLocation()))
        .address(toDocument(listing.getAddress()))
        .nearestTransit(toDocument(listing.getNearestTransit()))
        .nearbyPlacesDescription(listing.getNearbyPlacesDescription())
        .nearbyUniversityCodes(listing.getNearbyUniversityCodes())
        .building(toDocument(listing.getBuilding()))
        .propertyPolicies(toDocument(listing.getPropertyPolicies()))
        .facilities(toDocument(listing.getFacilities()))
        .roomOffers(listing.getRoomOffers().stream().map(ListingMongoMapper::toDocument).toList())
        .featureSummary(listing.getFeatureSummary())
        .descriptions(toDocument(listing.getDescriptions()))
        .extraNotes(listing.getExtraNotes())
        .imageUrls(listing.getImageUrls())
        .favoriteCount(listing.getFavoriteCount())
        .createdAt(listing.getCreatedAt())
        .updatedAt(listing.getUpdatedAt())
        .build();
  }

  /** Mongo GeoJSON Point를 도메인 좌표 값으로 변환한다. */
  private static Listing.GeoPoint toDomain(GeoJsonPoint point) {
    return new Listing.GeoPoint(point.getX(), point.getY());
  }

  /** 도메인 좌표 값을 2dsphere 인덱스에서 사용할 Mongo GeoJSON Point로 변환한다. */
  private static GeoJsonPoint toDocument(Listing.GeoPoint point) {
    return new GeoJsonPoint(point.longitude(), point.latitude());
  }

  /** 저장 문서의 주소 값을 도메인 주소 값으로 변환한다. */
  private static Listing.Address toDomain(ListingDocument.AddressDocument address) {
    return new Listing.Address(
        address.city(), address.district(), address.fullAddress(), address.detail());
  }

  /** 도메인 주소 값을 저장 문서 주소 값으로 변환한다. */
  private static ListingDocument.AddressDocument toDocument(Listing.Address address) {
    return new ListingDocument.AddressDocument(
        address.city(), address.district(), address.fullAddress(), address.detail());
  }

  /** 저장 문서의 가까운 교통 정보를 도메인 값으로 변환한다. */
  private static Listing.NearestTransit toDomain(ListingDocument.NearestTransitDocument transit) {
    return new Listing.NearestTransit(transit.type(), transit.name(), transit.walkMinutes());
  }

  /** 도메인 가까운 교통 정보를 저장 문서 값으로 변환한다. */
  private static ListingDocument.NearestTransitDocument toDocument(Listing.NearestTransit transit) {
    return new ListingDocument.NearestTransitDocument(
        transit.type(), transit.name(), transit.walkMinutes());
  }

  /** 저장 문서의 건물 정보를 도메인 값으로 변환한다. */
  private static Listing.Building toDomain(ListingDocument.BuildingDocument building) {
    return new Listing.Building(
        building.type(),
        building.usedFloorMin(),
        building.usedFloorMax(),
        building.totalFloors(),
        building.parkingAvailable(),
        building.elevatorAvailable(),
        building.heatingSystem());
  }

  /** 도메인 건물 정보를 저장 문서 값으로 변환한다. */
  private static ListingDocument.BuildingDocument toDocument(Listing.Building building) {
    return new ListingDocument.BuildingDocument(
        building.type(),
        building.usedFloorMin(),
        building.usedFloorMax(),
        building.totalFloors(),
        building.parkingAvailable(),
        building.elevatorAvailable(),
        building.heatingSystem());
  }

  /** 저장 문서의 매물 정책을 도메인 값으로 변환한다. */
  private static Listing.PropertyPolicies toDomain(
      ListingDocument.PropertyPoliciesDocument policies) {
    return new Listing.PropertyPolicies(
        policies.arcRequired(),
        policies.residentRegistrationAvailable(),
        policies.studySuitable(),
        policies.mealsProvided(),
        policies.englishAvailable());
  }

  /** 도메인 매물 정책을 저장 문서 값으로 변환한다. */
  private static ListingDocument.PropertyPoliciesDocument toDocument(
      Listing.PropertyPolicies policies) {
    return new ListingDocument.PropertyPoliciesDocument(
        policies.arcRequired(),
        policies.residentRegistrationAvailable(),
        policies.studySuitable(),
        policies.mealsProvided(),
        policies.englishAvailable());
  }

  /** 저장 문서의 공용 시설 정보를 도메인 값으로 변환한다. */
  private static Listing.Facilities toDomain(ListingDocument.FacilitiesDocument facilities) {
    List<Listing.CommonSpace> commonSpaces =
        facilities.commonSpaces().stream()
            .map(space -> new Listing.CommonSpace(space.type(), space.count()))
            .toList();
    return new Listing.Facilities(
        facilities.laundry(),
        facilities.livingAmenities(),
        facilities.securityFeatures(),
        commonSpaces,
        facilities.providedSupplies());
  }

  /** 도메인 공용 시설 정보를 저장 문서 값으로 변환한다. */
  private static ListingDocument.FacilitiesDocument toDocument(Listing.Facilities facilities) {
    List<ListingDocument.CommonSpaceDocument> commonSpaces =
        facilities.commonSpaces().stream()
            .map(space -> new ListingDocument.CommonSpaceDocument(space.type(), space.count()))
            .toList();
    return new ListingDocument.FacilitiesDocument(
        facilities.laundry(),
        facilities.livingAmenities(),
        facilities.securityFeatures(),
        commonSpaces,
        facilities.providedSupplies());
  }

  /** 저장 문서의 방 상품을 도메인 RoomOffer로 변환한다. */
  private static Listing.RoomOffer toDomain(ListingDocument.RoomOfferDocument roomOffer) {
    return new Listing.RoomOffer(
        roomOffer.roomOfferId().toHexString(),
        roomOffer.name(),
        roomOffer.status(),
        roomOffer.rentalType(),
        new Listing.Pricing(
            roomOffer.pricing().monthlyRent(),
            roomOffer.pricing().deposit(),
            roomOffer.pricing().maintenanceFee(),
            roomOffer.pricing().currency()),
        new Listing.Contract(
            roomOffer.contract().minStayMonths(),
            roomOffer.contract().maxStayMonths(),
            new Listing.RefundPolicy(
                roomOffer.contract().refundPolicy().code(),
                roomOffer.contract().refundPolicy().description())),
        new Listing.Inventory(
            roomOffer.inventory().totalCount(),
            roomOffer.inventory().availableCount(),
            roomOffer.inventory().nextAvailableFrom()),
        roomOffer.genderPolicy(),
        roomOffer.features(),
        roomOffer.filterTags(),
        roomOffer.roomImageUrls());
  }

  /** 도메인 RoomOffer를 저장 문서의 방 상품 구조로 변환한다. */
  private static ListingDocument.RoomOfferDocument toDocument(Listing.RoomOffer roomOffer) {
    return new ListingDocument.RoomOfferDocument(
        objectIdOrNew(roomOffer.roomOfferId(), "roomOfferId"),
        roomOffer.name(),
        roomOffer.status(),
        roomOffer.rentalType(),
        new ListingDocument.PricingDocument(
            roomOffer.pricing().monthlyRent(),
            roomOffer.pricing().deposit(),
            roomOffer.pricing().maintenanceFee(),
            roomOffer.pricing().currency()),
        new ListingDocument.ContractDocument(
            roomOffer.contract().minStayMonths(),
            roomOffer.contract().maxStayMonths(),
            new ListingDocument.RefundPolicyDocument(
                roomOffer.contract().refundPolicy().code(),
                roomOffer.contract().refundPolicy().description())),
        new ListingDocument.InventoryDocument(
            roomOffer.inventory().totalCount(),
            roomOffer.inventory().availableCount(),
            roomOffer.inventory().nextAvailableFrom()),
        roomOffer.genderPolicy(),
        roomOffer.features(),
        roomOffer.filterTags(),
        roomOffer.roomImageUrls());
  }

  /** 저장 문서의 다국어 설명을 도메인 값으로 변환한다. */
  private static Listing.Descriptions toDomain(ListingDocument.DescriptionsDocument descriptions) {
    return new Listing.Descriptions(descriptions.ko(), descriptions.en());
  }

  /** 도메인 다국어 설명을 저장 문서 값으로 변환한다. */
  private static ListingDocument.DescriptionsDocument toDocument(
      Listing.Descriptions descriptions) {
    return new ListingDocument.DescriptionsDocument(descriptions.ko(), descriptions.en());
  }

  private static ObjectId objectIdOrNew(String value, String field) {
    if (value == null) {
      return new ObjectId();
    }
    if (!ObjectId.isValid(value)) {
      throw new InvalidInputException(field + "는 24자리 ObjectId hex 문자열이어야 합니다.");
    }
    return new ObjectId(value);
  }
}
