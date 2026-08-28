package com.kohere.listing.domain;

import com.kohere.common.exception.InvalidInputException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import lombok.Builder;
import lombok.Getter;

/**
 * 주소와 공용시설을 공유하는 건물/숙소 단위 매물 애그리거트다.
 *
 * <p>이 모델은 MongoDB에 저장되는 v4 스키마의 의미를 코드로 표현한다. 매물 전체에 동일하게 적용되는 임대 방식·환불 정책·성별 정책·ARC 요구 여부는
 * Listing 루트가 소유하고, 가격·계약기간·방 이미지처럼 방 상품마다 달라지는 값만 {@link RoomOffer}가 소유한다.
 *
 * <p>도메인 모델은 MongoDB 타입이나 Spring Data 애노테이션에 의존하지 않는다. 저장 모델과의 변환은 infrastructure 계층이 담당한다.
 */
@Getter
@Builder(toBuilder = true)
public class Listing {

  /** 매물의 고유 식별자다. MongoDB 문서 id와 대응된다. */
  private final String id;

  /** 저장 스키마 버전이다. 레거시 문서 변환과 호환성 판단에 사용한다. */
  private final int schemaVersion;

  /** 매물을 소유하거나 관리하는 집주인 계정 id다. */
  private final Long landlordId;

  /**
   * 세입자에게 공개하는 매물 담당자 연락처다. {@code phone}은 지점 대표 전화이므로 매물(지점)마다 다른 값이고, 임대인 개인 연락처({@code
   * users.phone_number})는 이 문서에 복사하지 않는다(ADR-0039 Amended · ADR-0034 §6).
   */
  private final Contact contact;

  /** 임대인 사업자등록번호 원문이다. 세입자 응답에는 포함하지 않는다. */
  private final String businessRegistrationNumber;

  /** 매물 홍보용 블로그·홈페이지 주소다. 없을 수 있다. */
  private final String blogUrl;

  /** 입주 가능한 최소 연령이다. */
  private final int ageMin;

  /** 입주 가능한 최대 연령이다. */
  private final int ageMax;

  /** 목록과 상세 화면에 노출되는 한국어·영어 매물명이다. */
  private final LocalizedText title;

  /** 고시원, 코리빙, 쉐어하우스 같은 매물의 큰 분류다. */
  private final ListingType type;

  /** 매물 전체에 적용되는 임대 방식이다. */
  private final RentalType rentalType;

  /** 매물의 게시/심사 상태다. */
  private final ListingStatus status;

  /**
   * 관리자가 심사에서 반려한 사유다. 임대인만 읽는 값이라 번역하지 않는다.
   *
   * <p><b>{@code REJECTED}에서만 살아 있는 값이 아니다.</b> 임대인이 고쳐 다시 올리면 상태는 {@code PENDING}으로 가지만 이 값은 그대로
   * 남는다 — 임대인은 심사를 기다리는 동안 무엇을 고치라고 했는지 다시 볼 수 있고, 재심사하는 관리자는 이 매물이 전에 왜 반려됐는지 알 수 있다. 상태가 「지금 고쳐야
   * 한다({@code REJECTED})」와 「고쳐서 재심사 중({@code PENDING})」을 이미 구분해 주므로 값이 남아도 혼동되지 않는다.
   *
   * <p>지워지는 것은 <b>승인 시점</b>뿐이다({@link #approve}). 공개된 매물이 지난 반려 사유를 달고 다니지 않게 한다.
   */
  private final String rejectionReason;

  /** 성별 입주 제한 또는 분리 운영 정책이다. */
  private final GenderPolicy genderPolicy;

  /** 임대인이 응대할 수 있는 외국어 목록이다. */
  private final Set<SupportedLanguage> languagesSupported;

  /** 사용자가 찜한 누적 수 또는 현재 찜 수다. */
  private final int favoriteCount;

  /** 매물 대표 이미지 URL 목록이다. 방별 이미지는 {@link RoomOffer#roomImageUrls()}가 가진다. */
  private final List<String> imageUrls;

  /** 주변 대학교 코드 목록이다. 대학교 기반 검색 필터에 사용한다. */
  private final Set<String> nearbyUniversityCodes;

  /** 매물 최초 생성 시각이다. */
  private final Instant createdAt;

  /** 매물 정보가 마지막으로 수정된 시각이다. */
  private final Instant updatedAt;

  /** 행정구역, 전체 주소, 상세 주소를 포함한 주소 정보다. */
  private final Address address;

  /** 층수, 주차, 엘리베이터 등 건물 자체 정보다. */
  private final Building building;

  /** 한국어·영어 매물 소개 문구다. */
  private final LocalizedText description;

  /** 입주자가 알아야 할 유의사항이다. */
  private final LocalizedText extraNotes;

  /** 공용 시설과 제공 물품 정보다. */
  private final Facilities facilities;

  /**
   * 지도 검색과 거리 계산에 사용하는 좌표다.
   *
   * <p>등록은 주소 검색이 준 좌표를 요청으로 받아 채운다(ADR-0042). 저장 계약의 <b>필수</b> 필드이며 도메인·MongoDB validator가 함께 막는다.
   */
  private final GeoPoint location;

  /** 가장 가까운 지하철역과 도보 시간이다. */
  private final NearestTransit nearestTransit;

  /** 매물 주변 편의시설 목록이다. */
  private final Set<NearbyFacility> nearbyFacilities;

  /** 입주에 외국인등록증(ARC)이 필요한지 여부다. */
  private final ArcRequirement arcRequired;

  /** 매물 전체에 적용되는 환불 정책 문구다. */
  private final LocalizedText refundPolicy;

  /** 가격·계약기간·방 이미지가 다른 방 상품 목록이다. */
  private final List<RoomOffer> roomOffers;

  /** 임대인이 선호하는 입주자 국적이다. 등록 폼 설문 응답이라 세입자 응답에 포함하지 않는다. */
  private final Set<Nationality> preferredNationalities;

  /** 임대인이 외국인 계약에서 겪은 어려움이다. 등록 폼 설문 응답이라 세입자 응답에 포함하지 않는다. */
  private final Set<ContractDifficulty> contractDifficulties;

  /** 임대인이 서비스에 남긴 의견이다. 등록 폼 설문 응답이라 세입자 응답에 포함하지 않는다. */
  private final String serviceFeedback;

  /**
   * 심사를 통과시켜 공개 상태로 만든다.
   *
   * <p><b>상태를 가리지 않는다.</b> 심사 대기 매물의 승인뿐 아니라 <b>잘못 반려한 매물을 되살리는</b> 경로({@code REJECTED →
   * PUBLISHED})도 정상이다 — 관리자의 오판을 되돌릴 수단이 서버에 있어야 하기 때문이다. 임대인이 고쳐서 다시 올리는 경로({@link #afterEdit})와
   * 별개로, 관리자 단독으로도 되살릴 수 있다.
   *
   * <p>이미 공개 중인 매물을 다시 승인하면 <b>아무 일도 일어나지 않는다</b>. 같은 값으로 저장해도 결과는 같지만 {@code updatedAt}이 바뀌면 세입자
   * 목록의 기본 정렬(찜 수 → 최신 수정순)에서 그 매물만 위로 올라간다 — 눈에 띄지 않는 부작용이라 아예 손대지 않는다.
   *
   * <p>반려됐다 다시 올라온 매물이면 이전 사유가 남아 있다. 공개되는 매물이 지난 반려 사유를 달고 다니지 않도록 여기서 지운다.
   *
   * @param now 상태를 바꾼 시각
   * @return 공개 상태가 된 매물. 이미 공개 중이었다면 자기 자신
   */
  public Listing approve(Instant now) {
    if (status == ListingStatus.PUBLISHED) {
      return this;
    }
    return toBuilder().status(ListingStatus.PUBLISHED).rejectionReason(null).updatedAt(now).build();
  }

  /**
   * 사유와 함께 반려한다. 사유는 임대인만 읽는 값이라 번역하지 않는다.
   *
   * <p><b>상태를 가리지 않는다.</b> 심사 대기 매물의 1차 반려뿐 아니라, 공개 후 문제가 발견된 매물을 내리는 <b>사후 반려</b>({@code PUBLISHED
   * → REJECTED})와 이미 반려한 매물의 <b>사유 정정</b>({@code REJECTED → REJECTED})이 모두 정상 경로다. 승인과 달리 같은 상태로의
   * 재반려도 사유를 덮어써야 하므로 무시하지 않는다.
   *
   * @param reason 반려 사유
   * @param now 상태를 바꾼 시각
   * @return 반려 상태가 된 새 매물
   */
  public Listing reject(String reason, Instant now) {
    return toBuilder()
        .status(ListingStatus.REJECTED)
        .rejectionReason(reason)
        .updatedAt(now)
        .build();
  }

  /** 이 매물이 이 계정의 소유인지 본다. 임대인 전용 조회·수정의 소유권 게이트가 쓴다. */
  public boolean isOwnedBy(long userId) {
    return landlordId != null && landlordId == userId;
  }

  /**
   * 지금 임대인이 수정할 수 있는 상태인지 확인한다. 아니면 {@link ListingNotEditableException}이다.
   *
   * <p>사진을 확정 위치로 복사하기 <b>전에</b> 부른다 — 거절될 요청이 확정 위치에 흔적을 남기지 않게 하려는 것으로, 등록이 사진 키 검사를 가장 앞에 두는 것과
   * 같은 이유다.
   */
  public void requireEditable() {
    nextStatusAfterEdit();
  }

  /**
   * 수정이 반영된 빌더를 받아 전이를 마무리한다.
   *
   * <p><b>전이 상태를 서비스가 아니라 여기서 정한다.</b> 서비스가 정하게 하면 다음에 생기는 수정 경로가 규칙을 다시 쓰게 된다.
   *
   * <p><b>반려 사유는 건드리지 않는다.</b> 고쳐서 다시 올린 매물이 {@code PENDING}으로 가면서 사유를 그대로 들고 가는 것이 의도다 — 임대인은 심사를
   * 기다리는 동안 무엇을 고치라고 했는지 다시 볼 수 있고, 재심사하는 관리자는 이 매물이 전에 왜 반려됐는지 알 수 있다. 상태가 두 경우를 이미 구분하므로 값이 남아도
   * 혼동되지 않는다. 지우는 것은 {@link #approve} 하나뿐이다.
   *
   * @param edited 수정 요청이 반영된 빌더. 상태·수정 시각은 아직 이 매물의 값이다
   * @param now 수정한 시각
   */
  public Listing afterEdit(ListingBuilder edited, Instant now) {
    return edited.status(nextStatusAfterEdit()).updatedAt(now).build();
  }

  /**
   * 수정 후 넘어갈 상태를 정한다.
   *
   * <p><b>{@code default}를 두지 않는다.</b> 상태가 하나 더 늘면 컴파일이 깨져 여기서 결정을 강제한다 — 조용히 어느 한쪽으로 떨어지면 새 상태의 수정
   * 가능 여부가 아무도 정하지 않은 채 정해진다.
   */
  private ListingStatus nextStatusAfterEdit() {
    return switch (status) {
      case REJECTED -> ListingStatus.PENDING;
      case PUBLISHED -> ListingStatus.UPDATE_PENDING;
      case PENDING, UPDATE_PENDING -> throw new ListingNotEditableException();
    };
  }

  /** 매물의 심사·게시 상태다. 임대인과 관리자만 읽으므로 번역 대상이 아니다. */
  public enum ListingStatus {
    /** 등록 직후 관리자 승인을 기다리는 상태다. 조회 API에 노출되지 않는다. */
    PENDING,

    /** 승인되어 검색과 상세 화면에 공개된 상태다. */
    PUBLISHED,

    /** 관리자가 반려한 상태다. 사유는 {@link Listing#getRejectionReason()}에 담긴다. */
    REJECTED,

    /**
     * 공개 중이던 매물을 임대인이 수정해 재심사를 기다리는 상태다.
     *
     * <p>세입자 조회에서 <b>빠진다</b> — 심사를 거치지 않은 내용이 세입자에게 도달하지 않게 하려는 것이다. 조회 경로들이 {@code PUBLISHED}만
     * 통과시키므로 <b>아무것도 하지 않아도</b> 그렇게 되며, 승인되면 찜 수·찜 문서·최근 본 기록이 그대로 복구된다.
     */
    UPDATE_PENDING
  }

  /** 가장 가까운 대중교통 수단 유형이다. */
  public enum TransitType {
    /** 지하철역 기준이다. */
    SUBWAY
  }

  /** 건물의 물리적 형태 또는 주거 유형이다. */
  public enum BuildingType {
    /** 상가건물이다. */
    COMMERCIAL_BUILDING,

    /** 단독건물이다. */
    STANDALONE_BUILDING,

    /** 빌라 또는 연립주택이다. */
    VILLA,

    /** 단독주택이다. */
    DETACHED_HOUSE,

    /** 오피스텔이다. */
    OFFICETEL,

    /** 아파트다. */
    APARTMENT,

    /** 주상복합 건물이다. */
    MIXED_USE
  }

  /** 건물 또는 방에 적용되는 난방 방식이다. */
  public enum HeatingSystem {
    /** 중앙난방 방식이다. */
    CENTRAL,

    /** 개별난방 방식이다. */
    INDIVIDUAL,

    /** 난방시설이 없다. <b>단독으로만</b> 보낼 수 있다. */
    NONE
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
    MONTHLY_RENT
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
    STUDY_ROOM,

    /** 입주자가 함께 사용할 수 있는 회의실이다. */
    MEETING_ROOM,

    /** 입주자가 함께 사용할 수 있는 옥상 공간이다. */
    ROOFTOP,

    /** 공용공간이 없다. <b>단독으로만</b> 보낼 수 있다. */
    NONE
  }

  /**
   * 세입자가 매물 문의에 사용할 담당자 연락처다.
   *
   * <p>{@code phone}은 <b>지점 대표 전화</b>다. 임대인 개인 연락처는 여기에 담지 않는다 — 문자문의 번호({@code sms})가 그 통로였고, 임대인이
   * 거기 적는 값이 온보딩에서 인증한 개인 번호 자신이라 마스킹 대상 PII를 매물 응답으로 평문 공개하게 돼 제거했다(ADR-0039 Amended · ADR-0034
   * §6).
   */
  public record Contact(String managerName, String phone) {}

  /** MongoDB 2dsphere와 지도 표시에 쓰는 WGS84 좌표 값이다. */
  public record GeoPoint(double longitude, double latitude) {
    public GeoPoint {
      if (longitude < -180 || longitude > 180 || latitude < -90 || latitude > 90) {
        throw new InvalidInputException("유효하지 않은 WGS84 좌표입니다.");
      }
    }
  }

  /**
   * 행정구역 코드와 사용자에게 보여줄 다국어 주소를 묶은 값이다.
   *
   * <p>{@code city}/{@code district}는 검색에 쓰는 언어 무관 코드이므로 그대로 유지하고, 실제 화면에 노출되는 전체 주소와 상세 주소만
   * 한국어·영어로 저장한다. 상세 주소는 데이터에 없을 수 있으므로 {@code null}을 허용한다.
   */
  public record Address(
      String city, String district, LocalizedText fullAddress, LocalizedText detail) {}

  /** 매물에서 가장 가까운 지하철역과 도보 시간이다. 주변 편의시설은 루트 {@code nearbyFacilities}가 소유한다. */
  public record NearestTransit(TransitType type, LocalizedText name, int walkMinutes) {
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

  /** 세탁, 주방, 생활 편의, 보안, 공용공간, 제공 물품 등 건물 공용 시설 묶음이다. */
  public record Facilities(
      Set<HeatingSystem> heatingSystem,
      Set<KitchenFacility> kitchen,
      Set<LaundryFacility> laundry,
      Set<LivingAmenity> livingAmenities,
      Set<SecurityFeature> securityFeatures,
      Set<CommonSpaceType> commonSpaces,
      Set<ProvidedSupply> providedSupplies) {
    public Facilities {
      heatingSystem = Set.copyOf(heatingSystem);
      kitchen = Set.copyOf(kitchen);
      laundry = Set.copyOf(laundry);
      livingAmenities = Set.copyOf(livingAmenities);
      securityFeatures = Set.copyOf(securityFeatures);
      commonSpaces = Set.copyOf(commonSpaces);
      providedSupplies = Set.copyOf(providedSupplies);
    }
  }

  /**
   * 방 상품 단위의 월세·보증금·관리비 정보다.
   *
   * <p>주 단위 임대는 다루지 않는다. 주세를 받으려면 {@link RentalType}에 주간 계약이 생기고 월세·주세 중 하나만 받는 구조가 돼야 하는데, 예산
   * 필터·정렬·예약 총액이 모두 월세 전제라 그 확장은 MVP 범위 밖이다(ADR-0039).
   */
  public record Pricing(int monthlyRent, int deposit, int maintenanceFee, Currency currency) {
    public Pricing {
      if (monthlyRent < 0 || deposit < 0 || maintenanceFee < 0) {
        throw new InvalidInputException("가격은 0 이상이어야 합니다.");
      }
    }
  }

  /** 방 상품에 적용되는 최소·최대 계약기간이다. */
  public record Contract(int minStayMonths, int maxStayMonths) {
    public Contract {
      if (minStayMonths < 1 || maxStayMonths < minStayMonths) {
        throw new InvalidInputException("계약기간 범위가 올바르지 않습니다.");
      }
    }
  }

  /** 같은 가격·계약기간·검색 태그를 공유하는 방 묶음이다. */
  public record RoomOffer(
      String roomOfferId,
      LocalizedText name,
      RoomOfferStatus status,
      Contract contract,
      Pricing pricing,
      Set<ConditionTag> filterTags,
      List<String> roomImageUrls) {
    public RoomOffer {
      filterTags = Set.copyOf(filterTags);
      roomImageUrls = List.copyOf(roomImageUrls);
    }
  }
}
