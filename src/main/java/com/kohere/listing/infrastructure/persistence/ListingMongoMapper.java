package com.kohere.listing.infrastructure.persistence;

import com.kohere.common.exception.InvalidInputException;
import com.kohere.listing.domain.Listing;
import com.kohere.listing.domain.LocalizedText;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;

/**
 * 순수 도메인 모델과 MongoDB 저장 모델의 변환을 한곳에서 담당한다.
 *
 * <p>MongoDB 문서는 v4 저장 스키마를 따른다. 이 매퍼는 저장 구조와 도메인 구조를 1:1로 맞추는 역할만 하며, 응답 조립은 {@code
 * ListingResponseMapper}가 담당한다.
 */
final class ListingMongoMapper {

  private ListingMongoMapper() {}

  /** MongoDB 문서를 비즈니스 로직에서 쓰는 순수 도메인 모델로 변환한다. */
  static Listing toDomain(ListingDocument document) {
    return Listing.builder()
        .id(document.getId().toHexString())
        .schemaVersion(document.getSchemaVersion())
        .landlordId(document.getLandlordId())
        .contact(toDomain(document.getContact()))
        .businessRegistrationNumber(document.getBusinessRegistrationNumber())
        .blogUrl(document.getBlogUrl())
        .ageMin(document.getAgeMin())
        .ageMax(document.getAgeMax())
        .title(toDomain(document.getTitle()))
        .type(document.getType())
        .rentalType(document.getRentalType())
        .status(document.getStatus())
        .rejectionReason(document.getRejectionReason())
        .genderPolicy(document.getGenderPolicy())
        .languagesSupported(document.getLanguagesSupported())
        .favoriteCount(document.getFavoriteCount())
        .imageUrls(document.getImageUrls())
        .nearbyUniversityCodes(document.getNearbyUniversityCodes())
        .createdAt(document.getCreatedAt())
        .updatedAt(document.getUpdatedAt())
        .address(toDomain(document.getAddress()))
        .building(toDomain(document.getBuilding()))
        .description(toDomain(document.getDescription()))
        .extraNotes(toDomain(document.getExtraNotes()))
        .facilities(toDomain(document.getFacilities()))
        .location(toDomainNullable(document.getLocation()))
        .nearestTransit(toDomain(document.getNearestTransit()))
        .nearbyFacilities(document.getNearbyFacilities())
        .arcRequired(document.getArcRequired())
        .refundPolicy(toDomain(document.getRefundPolicy()))
        .roomOffers(document.getRoomOffers().stream().map(ListingMongoMapper::toDomain).toList())
        .preferredNationalities(document.getPreferredNationalities())
        .contractDifficulties(document.getContractDifficulties())
        .serviceFeedback(document.getServiceFeedback())
        .build();
  }

  /** 도메인 모델을 MongoDB 저장 전용 문서 구조로 변환한다. */
  static ListingDocument toDocument(Listing listing) {
    return ListingDocument.builder()
        .id(objectIdOrNew(listing.getId(), "listingId"))
        .schemaVersion(listing.getSchemaVersion())
        .landlordId(listing.getLandlordId())
        .contact(toDocument(listing.getContact()))
        .businessRegistrationNumber(listing.getBusinessRegistrationNumber())
        .blogUrl(listing.getBlogUrl())
        .ageMin(listing.getAgeMin())
        .ageMax(listing.getAgeMax())
        .title(toDocument(listing.getTitle()))
        .type(listing.getType())
        .rentalType(listing.getRentalType())
        .status(listing.getStatus())
        .rejectionReason(listing.getRejectionReason())
        .genderPolicy(listing.getGenderPolicy())
        .languagesSupported(listing.getLanguagesSupported())
        .favoriteCount(listing.getFavoriteCount())
        .imageUrls(listing.getImageUrls())
        .nearbyUniversityCodes(listing.getNearbyUniversityCodes())
        .createdAt(listing.getCreatedAt())
        .updatedAt(listing.getUpdatedAt())
        .address(toDocument(listing.getAddress()))
        .building(toDocument(listing.getBuilding()))
        .description(toDocument(listing.getDescription()))
        .extraNotes(toDocument(listing.getExtraNotes()))
        .facilities(toDocument(listing.getFacilities()))
        .location(toDocumentNullable(listing.getLocation()))
        .nearestTransit(toDocument(listing.getNearestTransit()))
        .nearbyFacilities(listing.getNearbyFacilities())
        .arcRequired(listing.getArcRequired())
        .refundPolicy(toDocument(listing.getRefundPolicy()))
        .roomOffers(listing.getRoomOffers().stream().map(ListingMongoMapper::toDocument).toList())
        .preferredNationalities(listing.getPreferredNationalities())
        .contractDifficulties(listing.getContractDifficulties())
        .serviceFeedback(listing.getServiceFeedback())
        .build();
  }

  /** Mongo GeoJSON Point를 도메인 좌표 값으로 변환한다. 지오코딩 전 매물은 좌표가 없을 수 있다. */
  private static Listing.GeoPoint toDomainNullable(GeoJsonPoint point) {
    return point == null ? null : new Listing.GeoPoint(point.getX(), point.getY());
  }

  /** 도메인 좌표 값을 2dsphere 인덱스에서 사용할 Mongo GeoJSON Point로 변환한다. */
  private static GeoJsonPoint toDocumentNullable(Listing.GeoPoint point) {
    return point == null ? null : new GeoJsonPoint(point.longitude(), point.latitude());
  }

  /** 저장 문서의 담당자 연락처를 도메인 값으로 변환한다. */
  private static Listing.Contact toDomain(ListingDocument.ContactDocument contact) {
    return new Listing.Contact(contact.managerName(), contact.phone());
  }

  /** 도메인 담당자 연락처를 저장 문서 값으로 변환한다. */
  private static ListingDocument.ContactDocument toDocument(Listing.Contact contact) {
    return new ListingDocument.ContactDocument(contact.managerName(), contact.phone());
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
        transit.type(), toDomain(transit.name()), transit.walkMinutes());
  }

  /** 도메인 가까운 교통 정보를 저장 문서 값으로 변환한다. */
  private static ListingDocument.NearestTransitDocument toDocument(Listing.NearestTransit transit) {
    if (transit == null) {
      return null;
    }
    return new ListingDocument.NearestTransitDocument(
        transit.type(), toDocument(transit.name()), transit.walkMinutes());
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

  /** 저장 문서의 공용 시설 정보를 도메인 값으로 변환한다. */
  private static Listing.Facilities toDomain(ListingDocument.FacilitiesDocument facilities) {
    return new Listing.Facilities(
        facilities.heatingSystem(),
        facilities.kitchen(),
        facilities.laundry(),
        facilities.livingAmenities(),
        facilities.securityFeatures(),
        facilities.commonSpaces(),
        facilities.providedSupplies());
  }

  /** 도메인 공용 시설 정보를 저장 문서 값으로 변환한다. */
  private static ListingDocument.FacilitiesDocument toDocument(Listing.Facilities facilities) {
    return new ListingDocument.FacilitiesDocument(
        facilities.heatingSystem(),
        facilities.kitchen(),
        facilities.laundry(),
        facilities.livingAmenities(),
        facilities.securityFeatures(),
        facilities.commonSpaces(),
        facilities.providedSupplies());
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
        toDomain(roomOffer.contract()),
        new Listing.Pricing(
            roomOffer.pricing().monthlyRent(),
            roomOffer.pricing().deposit(),
            roomOffer.pricing().maintenanceFee(),
            roomOffer.pricing().currency()),
        roomOffer.filterTags(),
        roomOffer.roomImageUrls());
  }

  /** 도메인 RoomOffer를 저장 문서의 방 상품 구조로 변환한다. */
  private static ListingDocument.RoomOfferDocument toDocument(Listing.RoomOffer roomOffer) {
    return new ListingDocument.RoomOfferDocument(
        objectIdHexOrNew(roomOffer.roomOfferId(), "roomOfferId"),
        toDocument(roomOffer.name()),
        roomOffer.status(),
        toDocument(roomOffer.contract()),
        new ListingDocument.PricingDocument(
            roomOffer.pricing().monthlyRent(),
            roomOffer.pricing().deposit(),
            roomOffer.pricing().maintenanceFee(),
            roomOffer.pricing().currency()),
        roomOffer.filterTags(),
        roomOffer.roomImageUrls());
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
   * <p>기존 API와 테스트가 24자리 ObjectId hex 형식을 전제로 하므로, 새 값이 없을 때는 ObjectId를 발급하되 MongoDB에는 문자열로 저장한다.
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
