package com.kohere.listing.domain;

import com.kohere.common.exception.InvalidInputException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import lombok.Builder;
import lombok.Getter;

/**
 * 주소와 공용시설을 공유하는 건물/숙소 단위 매물 애그리거트다.
 *
 * <p>이 모델은 MongoDB에 저장되는 v2 스키마의 의미를 코드로 표현한다. 매물 전체에 동일하게 적용되는 임대 방식·계약기간·환불 정책·성별 정책은 Listing 루트가
 * 소유하고, 가격·재고·방 이미지처럼 방 상품마다 달라지는 값만 {@link RoomOffer}가 소유한다. 프론트 API가 기존 응답 키를 유지해야 하는 경우에는
 * application 계층의 응답 매퍼가 루트 값을 방 응답에 다시 조립한다.
 *
 * <p>도메인 모델은 MongoDB 타입이나 Spring Data 애노테이션에 의존하지 않는다. 저장 모델과의 변환은 infrastructure 계층이 담당한다.
 */
@Getter
@Builder
public class Listing {

  /** 매물의 고유 식별자다. MongoDB 문서 id와 대응된다. */
  private final String id;

  /** 저장 스키마 버전이다. v1/v2 문서 변환과 호환성 판단에 사용한다. */
  private final int schemaVersion;

  /** 매물을 소유하거나 관리하는 집주인 계정 id다. */
  private final Long landlordId;

  /** 목록과 상세 화면에 노출되는 매물명이다. */
  private final String title;

  /** 고시원, 빌라, 아파트 같은 매물의 큰 분류다. */
  private final ListingType type;

  /** 매물의 게시/운영 상태다. */
  private final ListingStatus status;

  /** 월세, 전세처럼 매물 전체에 적용되는 임대 방식이다. */
  private final RentalType rentalType;

  /** 매물 전체에 적용되는 환불 정책이다. */
  private final RefundPolicy refundPolicy;

  /** 매물 전체에 적용되는 계약 가능 기간이다. */
  private final Contract contract;

  /** 성별 입주 제한 또는 분리 운영 정책이다. */
  private final GenderPolicy genderPolicy;

  /** 지도 검색과 거리 계산에 사용하는 좌표다. */
  private final GeoPoint location;

  /** 행정구역, 전체 주소, 상세 주소를 포함한 주소 정보다. */
  private final Address address;

  /** 가장 가까운 지하철/버스와 주변 안내 정보다. */
  private final NearestTransit nearestTransit;

  /** 주변 대학교 코드 목록이다. 대학교 기반 검색 필터에 사용한다. */
  private final Set<String> nearbyUniversityCodes;

  /** 층수, 주차, 엘리베이터 등 건물 자체 정보다. */
  private final Building building;

  /** 전입신고, 식사 제공, 영어 가능 여부 등 운영 정책이다. */
  private final PropertyPolicies propertyPolicies;

  /** 공용 시설과 제공 물품 정보다. */
  private final Facilities facilities;

  /** 가격·재고·방 이미지가 다른 방 상품 목록이다. */
  private final List<RoomOffer> roomOffers;

  /** 한국어/영어 상세 설명과 추가 유의사항이다. */
  private final Descriptions descriptions;

  /** 매물 대표 이미지 URL 목록이다. 방별 이미지는 {@link RoomOffer#roomImageUrls()}가 가진다. */
  private final List<String> imageUrls;

  /** 사용자가 찜한 누적 수 또는 현재 찜 수다. */
  private final int favoriteCount;

  /** 매물 최초 생성 시각이다. */
  private final Instant createdAt;

  /** 매물 정보가 마지막으로 수정된 시각이다. */
  private final Instant updatedAt;

  /** 매물이 외부에 노출되거나 관리되는 현재 상태다. */
  public enum ListingStatus {
    /** 작성 중이며 사용자에게 공개되지 않은 상태다. */
    DRAFT,

    /** 검색과 상세 화면에 공개된 상태다. */
    PUBLISHED,

    /** 일시적으로 공개를 중단한 상태다. */
    PAUSED,

    /** 삭제 처리되어 일반 조회에서 제외되는 상태다. */
    DELETED
  }

  /** 가장 가까운 대중교통 수단 유형이다. */
  public enum TransitType {
    /** 지하철역 기준이다. */
    SUBWAY,

    /** 버스 정류장 기준이다. */
    BUS
  }

  /** 건물의 물리적 형태 또는 주거 유형이다. */
  public enum BuildingType {
    /** 고시원 또는 고시텔 유형이다. */
    GOSIWON,

    /** 빌라 유형이다. */
    VILLA,

    /** 아파트 유형이다. */
    APARTMENT,

    /** 오피스텔 유형이다. */
    OFFICETEL,

    /** 위 분류에 들어가지 않는 기타 유형이다. */
    OTHER
  }

  /** 건물 또는 방에 적용되는 난방 방식이다. */
  public enum HeatingSystem {
    /** 중앙난방 방식이다. */
    CENTRAL,

    /** 개별난방 방식이다. */
    INDIVIDUAL,

    /** 지역난방 방식이다. */
    DISTRICT,

    /** 별도 분류가 필요한 기타 난방 방식이다. */
    OTHER
  }

  /** 방 상품의 판매 가능 상태다. */
  public enum RoomOfferStatus {
    /** 현재 계약 또는 문의가 가능한 상태다. */
    ACTIVE,

    /** 현재 계약 대상에서 제외된 상태다. */
    INACTIVE
  }

  /** 매물의 임대 계약 유형이다. */
  public enum RentalType {
    /** 월세 계약이다. */
    MONTHLY_RENT,

    /** 전세 계약이다. */
    JEONSE
  }

  /** 가격 표시에 사용하는 통화다. */
  public enum Currency {
    /** 대한민국 원화다. */
    KRW
  }

  /** 입주 가능 성별 또는 성별 분리 운영 정책이다. */
  public enum GenderPolicy {
    /** 성별 제한이 없다. */
    ANY,

    /** 여성만 입주할 수 있다. */
    FEMALE_ONLY,

    /** 남성만 입주할 수 있다. */
    MALE_ONLY,

    /** 성별에 따라 층이나 공간을 분리해 운영한다. */
    GENDER_SEPARATED
  }

  /** 방 상품 검색과 상세 노출에 쓰는 방 내부 특징이다. */
  public enum RoomFeature {
    /** 1인실이다. */
    SINGLE_ROOM,

    /** 2인실이다. */
    DOUBLE_ROOM,

    /** 방 내부에 전용 화장실 또는 욕실이 있다. */
    PRIVATE_BATH,

    /** 방 내부에 전용 냉장고가 있다. */
    PRIVATE_REFRIGERATOR,

    /** 방 내부 또는 제공 시설에 전자레인지가 있다. */
    MICROWAVE,

    /** 전기포트가 제공된다. */
    ELECTRIC_KETTLE
  }

  /** 건물 내 공용공간의 종류다. */
  public enum CommonSpaceType {
    /** 공용 주방이다. */
    SHARED_KITCHEN,

    /** 공용 화장실이다. */
    SHARED_TOILET,

    /** 공용 샤워실 또는 욕실이다. */
    SHARED_BATH,

    /** 휴게실 또는 라운지다. */
    LOUNGE,

    /** 공부방 또는 스터디룸이다. */
    STUDY_ROOM
  }

  /** 환불 정책을 API와 저장소에서 구분하기 위한 코드다. */
  public enum RefundPolicyCode {
    /** 입주 7일 전까지 전액 환불 가능한 정책이다. */
    FULL_REFUND_BEFORE_7_DAYS,

    /** 조건에 따라 일부 환불 가능한 정책이다. */
    PARTIAL_REFUND,

    /** 환불이 불가능한 정책이다. */
    NON_REFUNDABLE,

    /** 집주인 또는 서비스가 별도로 정의한 환불 정책이다. */
    CUSTOM
  }

  /** MongoDB 2dsphere와 지도 표시에 쓰는 WGS84 좌표 값이다. */
  public record GeoPoint(double longitude, double latitude) {
    public GeoPoint {
      if (longitude < -180 || longitude > 180 || latitude < -90 || latitude > 90) {
        throw new InvalidInputException("유효하지 않은 WGS84 좌표입니다.");
      }
    }
  }

  /** 행정구역과 화면 표시 주소를 묶은 주소 값이다. */
  public record Address(String city, String district, String fullAddress, String detail) {}

  /**
   * 매물에서 가장 가까운 대중교통과 도보 시간, 주변 편의시설 안내 문구를 나타낸다.
   *
   * <p>v1에서는 주변 편의시설 문구가 Listing 루트의 {@code nearbyPlacesDescription}에 있었지만, v2에서는 위치/교통 정보와 함께 다루기
   * 위해 가까운 교통 정보 하위 문서에 포함한다. API 응답은 호환을 위해 여전히 {@code locationInfo.nearbyPlacesDescription} 키로
   * 내려줄 수 있다.
   */
  public record NearestTransit(
      TransitType type, String name, int walkMinutes, String nearbyPlacesDescription) {
    public NearestTransit {
      if (walkMinutes < 0) {
        throw new InvalidInputException("도보 시간은 0 이상이어야 합니다.");
      }
    }
  }

  /** 층수, 주차, 엘리베이터처럼 건물 자체에 속한 정보다. */
  public record Building(
      BuildingType type,
      int usedFloorMin,
      int usedFloorMax,
      int totalFloors,
      boolean parkingAvailable,
      boolean elevatorAvailable) {}

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
      Set<HeatingSystem> heatingSystem,
      Set<String> kitchen,
      Set<String> laundry,
      Set<String> livingAmenities,
      Set<String> securityFeatures,
      List<CommonSpace> commonSpaces,
      Set<String> providedSupplies) {
    public Facilities {
      heatingSystem = Set.copyOf(heatingSystem);
      kitchen = Set.copyOf(kitchen);
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
        throw new InvalidInputException("가격은 0 이상이어야 합니다.");
      }
    }
  }

  /** 환불 정책 코드와 집주인/서비스 설명 문구를 함께 보관한다. */
  public record RefundPolicy(RefundPolicyCode code, String description) {}

  /** 매물 전체에 적용되는 최소·최대 계약기간이다. 환불 정책은 별도 루트 값인 {@link RefundPolicy}가 소유한다. */
  public record Contract(int minStayMonths, int maxStayMonths) {
    public Contract {
      if (minStayMonths < 1 || maxStayMonths < minStayMonths) {
        throw new InvalidInputException("계약기간 범위가 올바르지 않습니다.");
      }
    }
  }

  /** 같은 가격·조건을 가진 실제 방의 총수와 현재 계약 가능 수량이다. */
  public record Inventory(int totalCount, int availableCount, LocalDate nextAvailableFrom) {
    public Inventory {
      if (totalCount < 0 || availableCount < 0 || availableCount > totalCount) {
        throw new InvalidInputException("방 재고 수량이 올바르지 않습니다.");
      }
    }
  }

  /** 같은 가격·재고·검색 태그를 공유하는 방 묶음이다. 여러 실제 방은 availableCount로 관리한다. */
  public record RoomOffer(
      String roomOfferId,
      String name,
      RoomOfferStatus status,
      Pricing pricing,
      Inventory inventory,
      Set<ConditionTag> filterTags,
      List<String> roomImageUrls) {
    public RoomOffer {
      filterTags = Set.copyOf(filterTags);
      roomImageUrls = List.copyOf(roomImageUrls);
    }
  }

  /** 한국어·영어 상세 설명과 자유 입력 주의사항이다. */
  public record Descriptions(String ko, String en, String extraNotes) {}
}
