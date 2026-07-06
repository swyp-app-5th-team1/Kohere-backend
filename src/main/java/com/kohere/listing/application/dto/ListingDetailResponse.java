package com.kohere.listing.application.dto;

import com.kohere.listing.domain.ConditionTag;
import com.kohere.listing.domain.Listing;
import com.kohere.listing.domain.ListingType;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/** 매물 상세 응답 DTO. 상세 화면 섹션별로 렌더링하기 쉽도록 관련 필드를 객체 단위로 묶는다. */
public record ListingDetailResponse(
    String listingId,
    BasicInfo basicInfo,
    SummaryInfo summary,
    LocationInfo locationInfo,
    PropertyInfo propertyInfo,
    List<RoomOfferResponse> roomOffers,
    ContentInfo content,
    ReviewSummary reviewSummary,
    InteractionInfo interaction,
    Instant createdAt,
    Instant updatedAt) {

  /** 제목·유형·공개 상태처럼 상세 화면 상단에서 쓰는 기본 정보다. */
  public record BasicInfo(String title, ListingType type, Listing.ListingStatus status) {}

  /**
   * 상세 화면 상단과 탭 요약에 바로 쓰는 집계 정보다.
   *
   * <p>목록 카드와 같은 기준으로 활성 방 상품만 모아 월세·보증금·관리비 범위를 계산하고, 계약기간은 매물 공통 contract 값을 내려준다. 프론트가 방 목록을 다시
   * 순회해 같은 값을 재계산하면 목록과 상세의 표시 기준이 달라질 수 있으므로, 서버가 단일 기준으로 내려준다. 환산 가격(예: USD)은 환율 정책이 필요하므로 포함하지
   * 않고 프론트에서 계산한다.
   */
  public record SummaryInfo(
      int minMonthlyRent,
      int maxMonthlyRent,
      int minDeposit,
      int maxDeposit,
      int minMaintenanceFee,
      int maxMaintenanceFee,
      int minStayMonths,
      int maxStayMonths,
      int activeRoomOfferCount,
      int imageCount,
      List<ConditionTag> conditions) {}

  /** 지도 좌표, 주소, 주변 시설/학교처럼 위치 기반 표시에 쓰는 정보다. */
  public record LocationInfo(
      GeoPoint location,
      Listing.Address address,
      NearestTransitInfo nearestTransit,
      String nearbyPlacesDescription,
      Set<String> nearbyUniversityCodes) {}

  /** 프론트 지도 컴포넌트에서 바로 쓰기 쉬운 위도·경도 값이다. */
  public record GeoPoint(double lat, double lng) {}

  /**
   * 가까운 교통수단 응답 전용 값이다.
   *
   * <p>저장 스키마 v2에서는 주변 편의시설 문구가 {@code nearestTransit} 하위에 들어가지만, 기존 프론트 계약을 유지하기 위해 응답의 {@code
   * nearestTransit} 객체에는 기존 3개 필드만 담고 주변 편의시설 문구는 {@code locationInfo.nearbyPlacesDescription}로
   * 내려준다.
   */
  public record NearestTransitInfo(Listing.TransitType type, String name, int walkMinutes) {}

  /**
   * 건물 정보 응답 전용 값이다.
   *
   * <p>저장 스키마 v2에서는 난방 정보가 {@code facilities.heatingSystem[]}으로 이동했다. 하지만 기존 상세 응답 키인 {@code
   * propertyInfo.building.heatingSystem}을 유지하기 위해 응답 전용 객체에서 대표 난방 값을 함께 노출한다.
   */
  public record BuildingInfo(
      Listing.BuildingType type,
      int usedFloorMin,
      int usedFloorMax,
      int totalFloors,
      boolean parkingAvailable,
      boolean elevatorAvailable,
      Listing.HeatingSystem heatingSystem) {}

  /** 건물 자체의 시설·정책과 활성 방 상품들의 특징 합집합이다. */
  public record PropertyInfo(
      BuildingInfo building,
      Listing.PropertyPolicies propertyPolicies,
      Listing.Facilities facilities,
      Set<ConditionTag> featureSummary) {}

  /** 동일 가격·조건을 공유하는 실제 방 묶음 하나를 나타낸다. */
  public record RoomOfferResponse(
      String roomOfferId,
      String name,
      Listing.RoomOfferStatus status,
      Listing.RentalType rentalType,
      Listing.Pricing pricing,
      ContractResponse contract,
      Listing.Inventory inventory,
      Listing.GenderPolicy genderPolicy,
      Set<Listing.RoomFeature> features,
      Set<ConditionTag> filterTags,
      List<String> roomImageUrls) {}

  /** 최소·최대 계약기간과 환불 정책을 묶은 계약 정보다. */
  public record ContractResponse(
      int minStayMonths, int maxStayMonths, Listing.RefundPolicy refundPolicy) {}

  /** API 응답에서 기존처럼 한국어·영어 설명만 담는 상세 설명 값이다. */
  public record DescriptionsInfo(String ko, String en) {}

  /** 상세 설명, 이미지, 썸네일처럼 콘텐츠 렌더링에 쓰는 정보다. */
  public record ContentInfo(
      DescriptionsInfo descriptions,
      String extraNotes,
      List<String> imageUrls,
      String thumbnailUrl) {}

  /**
   * 리뷰 섹션을 위한 최소 요약이다.
   *
   * <p>현재 MVP에는 리뷰 도메인이 아직 없으므로 {@code reviewCount=0}을 내려준다. 실제 리뷰 목록·평점·요약 문구는 리뷰 기능 고도화 시 이 객체를
   * 확장해서 연결한다. 문의 수는 채팅/문의 기능이 아직 TODO 상태라 상세 응답에 노출하지 않는다.
   */
  public record ReviewSummary(int reviewCount) {}

  /** 현재 사용자와 매물의 상호작용 상태다. 찜 기능 연결 전에는 favorited=false로 내려간다. */
  public record InteractionInfo(boolean favorited, int favoriteCount) {}
}
