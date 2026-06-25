package com.kohere.listing.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import lombok.Builder;
import lombok.Getter;

/**
 * 주소와 공용시설을 공유하는 건물 매물 애그리거트다. 가격·계약·재고처럼 방마다 달라지는 값은 {@link RoomOffer}가 소유한다.
 *
 * <p>도메인 모델은 MongoDB 타입이나 Spring Data 애노테이션에 의존하지 않는다. 저장 모델과의 변환은 infrastructure 계층이 담당한다.
 */
@Getter
@Builder
public class Listing {

  private final String id;
  private final int schemaVersion;
  private final Long landlordId;
  private final String title;
  private final ListingType type;
  private final ListingStatus status;
  private final GeoPoint location;
  private final Address address;
  private final NearestTransit nearestTransit;
  private final String nearbyPlacesDescription;
  private final Set<String> nearbyUniversityCodes;
  private final Building building;
  private final PropertyPolicies propertyPolicies;
  private final Facilities facilities;
  private final List<RoomOffer> roomOffers;
  private final Set<ConditionTag> featureSummary;
  private final Descriptions descriptions;
  private final String extraNotes;
  private final List<String> imageUrls;
  private final int favoriteCount;
  private final Instant createdAt;
  private final Instant updatedAt;

  public enum ListingStatus {
    DRAFT,
    PUBLISHED,
    PAUSED,
    DELETED
  }

  public enum TransitType {
    SUBWAY,
    BUS
  }

  public enum BuildingType {
    GOSIWON,
    VILLA,
    APARTMENT,
    OFFICETEL,
    OTHER
  }

  public enum HeatingSystem {
    CENTRAL,
    INDIVIDUAL,
    DISTRICT,
    OTHER
  }

  public enum RoomOfferStatus {
    ACTIVE,
    INACTIVE
  }

  public enum RentalType {
    MONTHLY_RENT,
    JEONSE
  }

  public enum Currency {
    KRW
  }

  public enum GenderPolicy {
    ANY,
    FEMALE_ONLY,
    MALE_ONLY,
    GENDER_SEPARATED
  }

  public enum RoomFeature {
    SINGLE_ROOM,
    DOUBLE_ROOM,
    PRIVATE_TOILET,
    PRIVATE_BATH,
    PRIVATE_REFRIGERATOR,
    MICROWAVE,
    ELECTRIC_KETTLE
  }

  public enum CommonSpaceType {
    SHARED_KITCHEN,
    SHARED_TOILET,
    SHARED_BATH,
    LOUNGE,
    STUDY_ROOM
  }

  public enum RefundPolicyCode {
    FULL_REFUND_BEFORE_7_DAYS,
    PARTIAL_REFUND,
    NON_REFUNDABLE,
    CUSTOM
  }

  /** MongoDB 2dsphere와 지도 표시에 쓰는 WGS84 좌표 값이다. */
  public record GeoPoint(double longitude, double latitude) {
    public GeoPoint {
      if (longitude < -180 || longitude > 180 || latitude < -90 || latitude > 90) {
        throw new IllegalArgumentException("유효하지 않은 WGS84 좌표입니다.");
      }
    }
  }

  /** 행정구역과 화면 표시 주소를 묶은 주소 값이다. */
  public record Address(String city, String district, String fullAddress, String detail) {}

  /** 매물에서 가장 가까운 대중교통과 도보 시간을 나타낸다. */
  public record NearestTransit(TransitType type, String name, int walkMinutes) {
    public NearestTransit {
      if (walkMinutes < 0) {
        throw new IllegalArgumentException("도보 시간은 0 이상이어야 합니다.");
      }
    }
  }

  /** 층수, 주차, 엘리베이터, 난방처럼 건물 전체에 속한 정보다. */
  public record Building(
      BuildingType type,
      int usedFloorMin,
      int usedFloorMax,
      int totalFloors,
      boolean parkingAvailable,
      boolean elevatorAvailable,
      HeatingSystem heatingSystem) {}

  /** ARC, 전입신고, 식사, 영어 가능 여부처럼 건물/운영 정책에 가까운 조건이다. */
  public record PropertyPolicies(
      boolean arcRequired,
      boolean residentRegistrationAvailable,
      boolean studySuitable,
      boolean mealsProvided,
      boolean englishAvailable) {}

  /** 공용 주방·화장실 같은 공용공간 유형과 수량이다. */
  public record CommonSpace(CommonSpaceType type, Integer count) {}

  /** 세탁, 생활 편의, 보안, 제공 물품 등 건물 공용 시설 묶음이다. */
  public record Facilities(
      Set<String> laundry,
      Set<String> livingAmenities,
      Set<String> securityFeatures,
      List<CommonSpace> commonSpaces,
      Set<String> providedSupplies) {
    public Facilities {
      laundry = Set.copyOf(laundry);
      livingAmenities = Set.copyOf(livingAmenities);
      securityFeatures = Set.copyOf(securityFeatures);
      commonSpaces = List.copyOf(commonSpaces);
      providedSupplies = Set.copyOf(providedSupplies);
    }
  }

  /** 방 상품 단위의 월세·보증금·관리비 정보다. */
  public record Pricing(int monthlyRent, int deposit, int maintenanceFee, Currency currency) {
    public Pricing {
      if (monthlyRent < 0 || deposit < 0 || maintenanceFee < 0) {
        throw new IllegalArgumentException("가격은 0 이상이어야 합니다.");
      }
    }
  }

  /** 환불 정책 코드와 집주인/서비스 설명 문구를 함께 보관한다. */
  public record RefundPolicy(RefundPolicyCode code, String description) {}

  /** 방 상품 단위의 최소·최대 계약기간과 환불 정책이다. */
  public record Contract(int minStayMonths, int maxStayMonths, RefundPolicy refundPolicy) {
    public Contract {
      if (minStayMonths < 1 || maxStayMonths < minStayMonths) {
        throw new IllegalArgumentException("계약기간 범위가 올바르지 않습니다.");
      }
    }
  }

  /** 같은 가격·조건을 가진 실제 방의 총수와 현재 계약 가능 수량이다. */
  public record Inventory(int totalCount, int availableCount, LocalDate nextAvailableFrom) {
    public Inventory {
      if (totalCount < 0 || availableCount < 0 || availableCount > totalCount) {
        throw new IllegalArgumentException("방 재고 수량이 올바르지 않습니다.");
      }
    }
  }

  /** 같은 가격·계약·조건을 공유하는 방 묶음이다. 여러 실제 방은 availableCount로 관리한다. */
  public record RoomOffer(
      String roomOfferId,
      String name,
      RoomOfferStatus status,
      RentalType rentalType,
      Pricing pricing,
      Contract contract,
      Inventory inventory,
      GenderPolicy genderPolicy,
      Set<RoomFeature> features,
      Set<ConditionTag> filterTags,
      List<String> roomImageUrls) {
    public RoomOffer {
      features = Set.copyOf(features);
      filterTags = Set.copyOf(filterTags);
      roomImageUrls = List.copyOf(roomImageUrls);
    }
  }

  /** 한국어·영어 상세 설명 문구다. */
  public record Descriptions(String ko, String en) {}
}
