package com.kohere.listing.infrastructure.persistence;

import com.kohere.listing.domain.ArcRequirement;
import com.kohere.listing.domain.ConditionTag;
import com.kohere.listing.domain.ContractDifficulty;
import com.kohere.listing.domain.KitchenFacility;
import com.kohere.listing.domain.LaundryFacility;
import com.kohere.listing.domain.Listing;
import com.kohere.listing.domain.ListingType;
import com.kohere.listing.domain.LivingAmenity;
import com.kohere.listing.domain.Nationality;
import com.kohere.listing.domain.NearbyFacility;
import com.kohere.listing.domain.ProvidedSupply;
import com.kohere.listing.domain.SecurityFeature;
import com.kohere.listing.domain.SupportedLanguage;
import java.time.Instant;
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
 * <p>이 클래스는 실제 MongoDB 문서(v4 스키마)의 필드 배치를 그대로 표현한다. 도메인 모델과의 변환은 {@link ListingMongoMapper}가 담당한다.
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
  private final ContactDocument contact;
  private final String businessRegistrationNumber;
  private final String blogUrl;
  private final int ageMin;
  private final int ageMax;
  private final LocalizedTextDocument title;
  private final ListingType type;
  private final Listing.RentalType rentalType;
  private final Listing.ListingStatus status;
  private final String rejectionReason;
  private final Listing.GenderPolicy genderPolicy;
  private final Set<SupportedLanguage> languagesSupported;
  private final int favoriteCount;
  private final List<String> imageUrls;
  private final Set<String> nearbyUniversityCodes;
  private final Instant createdAt;
  private final Instant updatedAt;
  private final AddressDocument address;
  private final BuildingDocument building;
  private final LocalizedTextDocument description;
  private final LocalizedTextDocument extraNotes;
  private final FacilitiesDocument facilities;
  private final GeoJsonPoint location;
  private final NearestTransitDocument nearestTransit;
  private final Set<NearbyFacility> nearbyFacilities;
  private final ArcRequirement arcRequired;
  private final LocalizedTextDocument refundPolicy;
  private final List<RoomOfferDocument> roomOffers;
  private final Set<Nationality> preferredNationalities;
  private final Set<ContractDifficulty> contractDifficulties;
  private final String serviceFeedback;

  /** MongoDB에 공통으로 저장되는 {@code {ko, en}} 다국어 문구 하위 문서다. */
  record LocalizedTextDocument(String ko, String en) {}

  /**
   * MongoDB에 저장되는 매물 담당자 연락처 하위 문서다. {@code phone}은 지점 대표 전화이며, 임대인 개인 연락처는 이 문서에 복사하지 않는다(ADR-0039
   * Amended · changeUnit {@code 0119}).
   */
  record ContactDocument(String managerName, String phone) {}

  /** MongoDB에 저장되는 주소 하위 문서다. 검색 코드는 enum, 표시 주소는 다국어 문서로 보관한다. */
  record AddressDocument(
      String city,
      String district,
      LocalizedTextDocument fullAddress,
      LocalizedTextDocument detail) {}

  /** MongoDB에 저장되는 가까운 교통 정보 하위 문서다. */
  record NearestTransitDocument(
      Listing.TransitType type, LocalizedTextDocument name, int walkMinutes) {}

  /** MongoDB에 저장되는 건물 정보 하위 문서다. */
  record BuildingDocument(
      Listing.BuildingType type,
      int usedFloorMin,
      int usedFloorMax,
      int totalFloors,
      boolean parkingAvailable,
      boolean elevatorAvailable) {}

  /** MongoDB에 저장되는 공용 시설 하위 문서다. */
  record FacilitiesDocument(
      Set<Listing.HeatingSystem> heatingSystem,
      Set<KitchenFacility> kitchen,
      Set<LaundryFacility> laundry,
      Set<LivingAmenity> livingAmenities,
      Set<SecurityFeature> securityFeatures,
      Set<Listing.CommonSpaceType> commonSpaces,
      Set<ProvidedSupply> providedSupplies) {}

  /** MongoDB에 저장되는 방 상품 가격 하위 문서다. */
  record PricingDocument(
      int monthlyRent, int deposit, int maintenanceFee, Listing.Currency currency) {}

  /** MongoDB에 저장되는 계약 조건 하위 문서다. */
  record ContractDocument(int minStayMonths, int maxStayMonths) {}

  /** MongoDB에 저장되는 방 상품 하위 문서다. */
  record RoomOfferDocument(
      String roomOfferId,
      LocalizedTextDocument name,
      Listing.RoomOfferStatus status,
      ContractDocument contract,
      PricingDocument pricing,
      Set<ConditionTag> filterTags,
      List<String> roomImageUrls) {}
}
