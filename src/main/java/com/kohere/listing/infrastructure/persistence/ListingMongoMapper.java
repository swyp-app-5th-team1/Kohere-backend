package com.kohere.listing.infrastructure.persistence;

import com.kohere.common.exception.InvalidInputException;
import com.kohere.listing.domain.Listing;
import com.kohere.listing.domain.LocalizedText;
import java.util.List;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;

/**
 * 순수 도메인 모델과 MongoDB 저장 모델의 변환을 한곳에서 담당한다.
 *
 * <p>MongoDB 문서는 v3 저장 스키마를 따른다. v3에서는 매물 전체에 공통인 임대 방식·계약기간·환불 정책·성별 정책이 루트에 있고, 방 상품에는 가격·재고·검색
 * 태그만 남는다. 이 매퍼는 저장 구조와 도메인 구조를 1:1로 맞추는 역할만 하며, 프론트 호환 응답 조립은 {@code ListingResponseMapper}에서
 * 담당한다.
 */
final class ListingMongoMapper {

  private ListingMongoMapper() {}

  /** MongoDB 문서를 비즈니스 로직에서 쓰는 순수 도메인 모델로 변환한다. */
  static Listing toDomain(ListingDocument document) {
    return Listing.builder()
        .id(document.getId().toHexString())
        .schemaVersion(document.getSchemaVersion())
        .landlordId(document.getLandlordId())
        .title(toDomain(document.getTitle()))
        .type(document.getType())
        .status(document.getStatus())
        .rentalType(document.getRentalType())
        .refundPolicy(toDomain(document.getRefundPolicy()))
        .contract(toDomain(document.getContract()))
        .genderPolicy(document.getGenderPolicy())
        .location(toDomain(document.getLocation()))
        .address(toDomain(document.getAddress()))
        .nearestTransit(toDomain(document.getNearestTransit()))
        .nearbyUniversityCodes(document.getNearbyUniversityCodes())
        .building(toDomain(document.getBuilding()))
        .propertyPolicies(toDomain(document.getPropertyPolicies()))
        .facilities(toDomain(document.getFacilities()))
        .roomOffers(document.getRoomOffers().stream().map(ListingMongoMapper::toDomain).toList())
        .descriptions(toDomain(document.getDescriptions()))
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
        .title(toDocument(listing.getTitle()))
        .type(listing.getType())
        .status(listing.getStatus())
        .rentalType(listing.getRentalType())
        .refundPolicy(toDocument(listing.getRefundPolicy()))
        .contract(toDocument(listing.getContract()))
        .genderPolicy(listing.getGenderPolicy())
        .location(toDocument(listing.getLocation()))
        .address(toDocument(listing.getAddress()))
        .nearestTransit(toDocument(listing.getNearestTransit()))
        .nearbyUniversityCodes(listing.getNearbyUniversityCodes())
        .building(toDocument(listing.getBuilding()))
        .propertyPolicies(toDocument(listing.getPropertyPolicies()))
        .facilities(toDocument(listing.getFacilities()))
        .roomOffers(listing.getRoomOffers().stream().map(ListingMongoMapper::toDocument).toList())
        .descriptions(toDocument(listing.getDescriptions()))
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
        address.city(),
        address.district(),
        toDomain(address.fullAddress()),
        toDomainNullable(address.detail()));
  }

  /** 도메인 주소 값을 저장 문서 주소 값으로 변환한다. */
  private static ListingDocument.AddressDocument toDocument(Listing.Address address) {
    return new ListingDocument.AddressDocument(
        address.city(),
        address.district(),
        toDocument(address.fullAddress()),
        toDocumentNullable(address.detail()));
  }

  /** 저장 문서의 가까운 교통 정보를 도메인 값으로 변환한다. */
  private static Listing.NearestTransit toDomain(ListingDocument.NearestTransitDocument transit) {
    if (transit == null) {
      return null;
    }
    return new Listing.NearestTransit(
        transit.type(),
        toDomain(transit.name()),
        transit.walkMinutes(),
        toDomain(transit.nearbyPlacesDescription()));
  }

  /** 도메인 가까운 교통 정보를 저장 문서 값으로 변환한다. */
  private static ListingDocument.NearestTransitDocument toDocument(Listing.NearestTransit transit) {
    if (transit == null) {
      return null;
    }
    return new ListingDocument.NearestTransitDocument(
        transit.type(),
        toDocument(transit.name()),
        transit.walkMinutes(),
        toDocument(transit.nearbyPlacesDescription()));
  }

  /** 저장 문서의 건물 정보를 도메인 값으로 변환한다. */
  private static Listing.Building toDomain(ListingDocument.BuildingDocument building) {
    return new Listing.Building(
        building.type(),
        building.usedFloorMin(),
        building.usedFloorMax(),
        building.totalFloors(),
        building.parkingAvailable(),
        building.elevatorAvailable());
  }

  /** 도메인 건물 정보를 저장 문서 값으로 변환한다. */
  private static ListingDocument.BuildingDocument toDocument(Listing.Building building) {
    return new ListingDocument.BuildingDocument(
        building.type(),
        building.usedFloorMin(),
        building.usedFloorMax(),
        building.totalFloors(),
        building.parkingAvailable(),
        building.elevatorAvailable());
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
        facilities.heatingSystem(),
        facilities.kitchen(),
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
        facilities.heatingSystem(),
        facilities.kitchen(),
        facilities.laundry(),
        facilities.livingAmenities(),
        facilities.securityFeatures(),
        commonSpaces,
        facilities.providedSupplies());
  }

  /** 저장 문서의 환불 정책을 도메인 값으로 변환한다. */
  private static Listing.RefundPolicy toDomain(ListingDocument.RefundPolicyDocument refundPolicy) {
    return new Listing.RefundPolicy(refundPolicy.code(), toDomain(refundPolicy.description()));
  }

  /** 도메인 환불 정책을 저장 문서 값으로 변환한다. */
  private static ListingDocument.RefundPolicyDocument toDocument(
      Listing.RefundPolicy refundPolicy) {
    return new ListingDocument.RefundPolicyDocument(
        refundPolicy.code(), toDocument(refundPolicy.description()));
  }

  /** 저장 문서의 계약기간을 도메인 값으로 변환한다. */
  private static Listing.Contract toDomain(ListingDocument.ContractDocument contract) {
    return new Listing.Contract(contract.minStayMonths(), contract.maxStayMonths());
  }

  /** 도메인 계약기간을 저장 문서 값으로 변환한다. */
  private static ListingDocument.ContractDocument toDocument(Listing.Contract contract) {
    return new ListingDocument.ContractDocument(contract.minStayMonths(), contract.maxStayMonths());
  }

  /** 저장 문서의 방 상품을 도메인 RoomOffer로 변환한다. */
  private static Listing.RoomOffer toDomain(ListingDocument.RoomOfferDocument roomOffer) {
    return new Listing.RoomOffer(
        roomOffer.roomOfferId(),
        toDomain(roomOffer.name()),
        roomOffer.status(),
        new Listing.Pricing(
            roomOffer.pricing().monthlyRent(),
            roomOffer.pricing().deposit(),
            roomOffer.pricing().maintenanceFee(),
            roomOffer.pricing().currency()),
        new Listing.Inventory(
            roomOffer.inventory().totalCount(),
            roomOffer.inventory().availableCount(),
            roomOffer.inventory().nextAvailableFrom()),
        roomOffer.filterTags(),
        roomOffer.roomImageUrls());
  }

  /** 도메인 RoomOffer를 저장 문서의 방 상품 구조로 변환한다. */
  private static ListingDocument.RoomOfferDocument toDocument(Listing.RoomOffer roomOffer) {
    return new ListingDocument.RoomOfferDocument(
        objectIdHexOrNew(roomOffer.roomOfferId(), "roomOfferId"),
        toDocument(roomOffer.name()),
        roomOffer.status(),
        new ListingDocument.PricingDocument(
            roomOffer.pricing().monthlyRent(),
            roomOffer.pricing().deposit(),
            roomOffer.pricing().maintenanceFee(),
            roomOffer.pricing().currency()),
        new ListingDocument.InventoryDocument(
            roomOffer.inventory().totalCount(),
            roomOffer.inventory().availableCount(),
            roomOffer.inventory().nextAvailableFrom()),
        roomOffer.filterTags(),
        roomOffer.roomImageUrls());
  }

  /** 저장 문서의 다국어 설명을 도메인 값으로 변환한다. */
  private static Listing.Descriptions toDomain(ListingDocument.DescriptionsDocument descriptions) {
    return new Listing.Descriptions(
        descriptions.ko(), descriptions.en(), descriptions.extraNotes());
  }

  /** 도메인 다국어 설명을 저장 문서 값으로 변환한다. */
  private static ListingDocument.DescriptionsDocument toDocument(
      Listing.Descriptions descriptions) {
    return new ListingDocument.DescriptionsDocument(
        descriptions.ko(), descriptions.en(), descriptions.extraNotes());
  }

  /** MongoDB의 다국어 하위 문서를 도메인 값 객체로 변환한다. */
  private static LocalizedText toDomain(ListingDocument.LocalizedTextDocument text) {
    if (text == null) {
      throw new InvalidInputException("필수 다국어 문구가 누락되었습니다.");
    }
    return new LocalizedText(text.ko(), text.en());
  }

  /** nullable 다국어 하위 문서를 변환한다. 주소 상세처럼 값 자체가 없을 수 있는 필드에만 사용한다. */
  private static LocalizedText toDomainNullable(ListingDocument.LocalizedTextDocument text) {
    return text == null ? null : toDomain(text);
  }

  /** 도메인 다국어 값을 MongoDB 저장 하위 문서로 변환한다. */
  private static ListingDocument.LocalizedTextDocument toDocument(LocalizedText text) {
    return new ListingDocument.LocalizedTextDocument(text.ko(), text.en());
  }

  /** nullable 다국어 값을 MongoDB 저장 하위 문서로 변환한다. */
  private static ListingDocument.LocalizedTextDocument toDocumentNullable(LocalizedText text) {
    return text == null ? null : toDocument(text);
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

  /**
   * 중첩 방 상품 식별자를 Mongo ObjectId 문자열로 정규화한다.
   *
   * <p>v2 저장 샘플은 {@code roomOfferId}를 문자열로 둔다. 그래도 기존 API와 테스트가 24자리 ObjectId hex 형식을 전제로 하므로, 새 값이
   * 없을 때는 ObjectId를 발급하되 MongoDB에는 문자열로 저장한다.
   */
  private static String objectIdHexOrNew(String value, String field) {
    if (value == null) {
      return new ObjectId().toHexString();
    }
    if (!ObjectId.isValid(value)) {
      throw new InvalidInputException(field + "는 24자리 ObjectId hex 문자열이어야 합니다.");
    }
    return value;
  }
}
