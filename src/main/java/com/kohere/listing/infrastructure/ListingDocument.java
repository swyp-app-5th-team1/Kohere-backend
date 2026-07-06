package com.kohere.listing.infrastructure;

import com.kohere.listing.domain.ConditionTag;
import com.kohere.listing.domain.Listing;
import com.kohere.listing.domain.ListingType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.MongoId;

/**
 * MongoDB {@code listings} 컬렉션 전용 저장 모델.
 *
 * <p>이 클래스는 실제 MongoDB 문서(v2 스키마)의 필드 배치를 그대로 표현한다. 따라서 프론트 응답 호환을 위해 유지되는 과거 API 키와는 다를 수 있다. 예를
 * 들어 임대 방식·계약기간·환불 정책·성별 정책은 DB에는 Listing 루트에 저장하지만, 상세 API 응답에서는 기존 계약을 지키기 위해 roomOffer 응답에 다시
 * 조립해서 내려준다. 도메인 모델과의 변환은 {@link ListingMongoMapper}가 담당한다.
 */
@Document(collection = ListingDocument.COLLECTION_NAME)
@Getter
@Builder
@AllArgsConstructor
class ListingDocument {

  static final String COLLECTION_NAME = "listings";

  @MongoId private final ObjectId id;
  private final int schemaVersion;
  private final Long landlordId;
  private final String title;
  private final ListingType type;
  private final Listing.ListingStatus status;
  private final Listing.RentalType rentalType;
  private final RefundPolicyDocument refundPolicy;
  private final ContractDocument contract;
  private final Listing.GenderPolicy genderPolicy;
  private final GeoJsonPoint location;
  private final AddressDocument address;
  private final NearestTransitDocument nearestTransit;
  private final Set<String> nearbyUniversityCodes;
  private final BuildingDocument building;
  private final PropertyPoliciesDocument propertyPolicies;
  private final FacilitiesDocument facilities;
  private final List<RoomOfferDocument> roomOffers;
  private final DescriptionsDocument descriptions;
  private final List<String> imageUrls;
  private final int favoriteCount;
  private final Instant createdAt;
  private final Instant updatedAt;

  /** MongoDB에 저장되는 주소 하위 문서다. */
  record AddressDocument(String city, String district, String fullAddress, String detail) {}

  /** MongoDB에 저장되는 가까운 교통 정보와 주변 편의시설 안내 하위 문서다. */
  record NearestTransitDocument(
      Listing.TransitType type, String name, int walkMinutes, String nearbyPlacesDescription) {}

  /** MongoDB에 저장되는 건물 정보 하위 문서다. */
  record BuildingDocument(
      Listing.BuildingType type,
      int usedFloorMin,
      int usedFloorMax,
      int totalFloors,
      boolean parkingAvailable,
      boolean elevatorAvailable) {}

  /** MongoDB에 저장되는 매물 운영 정책 하위 문서다. */
  record PropertyPoliciesDocument(
      boolean arcRequired,
      boolean residentRegistrationAvailable,
      boolean studySuitable,
      boolean mealsProvided,
      boolean englishAvailable) {}

  /** MongoDB에 저장되는 공용공간 하위 문서다. */
  record CommonSpaceDocument(Listing.CommonSpaceType type, Integer count) {}

  /** MongoDB에 저장되는 공용 시설 하위 문서다. */
  record FacilitiesDocument(
      Set<Listing.HeatingSystem> heatingSystem,
      Set<String> kitchen,
      Set<String> laundry,
      Set<String> livingAmenities,
      Set<String> securityFeatures,
      List<CommonSpaceDocument> commonSpaces,
      Set<String> providedSupplies) {}

  /** MongoDB에 저장되는 방 상품 가격 하위 문서다. */
  record PricingDocument(
      int monthlyRent, int deposit, int maintenanceFee, Listing.Currency currency) {}

  /** MongoDB에 저장되는 환불 정책 하위 문서다. */
  record RefundPolicyDocument(Listing.RefundPolicyCode code, String description) {}

  /** MongoDB에 저장되는 계약 조건 하위 문서다. */
  record ContractDocument(int minStayMonths, int maxStayMonths) {}

  /** MongoDB에 저장되는 방 재고 하위 문서다. */
  record InventoryDocument(int totalCount, int availableCount, LocalDate nextAvailableFrom) {}

  /** MongoDB에 저장되는 방 상품 하위 문서다. */
  record RoomOfferDocument(
      String roomOfferId,
      String name,
      Listing.RoomOfferStatus status,
      PricingDocument pricing,
      InventoryDocument inventory,
      Set<ConditionTag> filterTags,
      List<String> roomImageUrls) {}

  /** MongoDB에 저장되는 다국어 설명과 자유 입력 주의사항 하위 문서다. */
  record DescriptionsDocument(String ko, String en, String extraNotes) {}
}
