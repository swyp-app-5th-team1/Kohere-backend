package com.kohere.listing.application.dto;

import com.kohere.listing.domain.Listing;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * 매물 상세 화면 응답이다.
 *
 * <p>프론트는 {@link CodeLabelResponse#label()}을 화면에 표시하고 {@link CodeLabelResponse#code()}를 필터 요청이나 내부
 * 조건 비교에 사용한다. 매물명·주소·역명·방 이름·설명은 사용자 언어에 맞는 문자열 하나만 내려가므로 프론트에서 ko/en을 다시 선택할 필요가 없다.
 *
 * @param status 게시 상태는 일반 사용자 UI의 번역 대상이 아닌 서버 내부 상태이므로 기존 enum 값으로 유지한다.
 * @param conditions 상세 상단 조건 배지. 각 항목의 label을 표시하고 code는 필터 요청에 사용한다.
 */
public record ListingDetailResponse(
    String listingId,
    String title,
    CodeLabelResponse type,
    Listing.ListingStatus status,
    CodeLabelResponse rentalType,
    RefundPolicyResponse refundPolicy,
    Listing.Contract contract,
    CodeLabelResponse genderPolicy,
    GeoPoint location,
    AddressResponse address,
    NearestTransitResponse nearestTransit,
    Set<String> nearbyUniversityCodes,
    BuildingResponse building,
    Listing.PropertyPolicies propertyPolicies,
    FacilitiesResponse facilities,
    List<CodeLabelResponse> conditions,
    List<RoomOfferResponse> roomOffers,
    DescriptionsResponse descriptions,
    List<String> imageUrls,
    boolean favorited,
    int favoriteCount,
    Instant createdAt,
    Instant updatedAt) {

  /** 프론트 지도 컴포넌트에서 바로 쓰는 위도·경도 값이다. */
  public record GeoPoint(double lat, double lng) {}

  /** 검색용 행정 코드는 유지하고, 화면 주소만 사용자 언어로 선택한 응답이다. */
  public record AddressResponse(String city, String district, String fullAddress, String detail) {}

  /** 가까운 교통수단의 code/label과 사용자 언어의 역명·주변 안내를 담는다. */
  public record NearestTransitResponse(
      CodeLabelResponse type, String name, int walkMinutes, String nearbyPlacesDescription) {}

  /** 건물 종류는 code/label로, 숫자와 boolean은 언어와 무관한 원래 값으로 내린다. */
  public record BuildingResponse(
      CodeLabelResponse type,
      int usedFloorMin,
      int usedFloorMax,
      int totalFloors,
      boolean parkingAvailable,
      boolean elevatorAvailable) {}

  /** 환불 정책 코드는 기존 의미를 유지하고 설명 문장만 사용자 언어로 선택한다. */
  public record RefundPolicyResponse(String code, String description) {}

  /** 시설 그룹별 공통 코드를 모두 code/label 형태로 내려주는 상세 응답이다. */
  public record FacilitiesResponse(
      List<CodeLabelResponse> heatingSystem,
      List<CodeLabelResponse> kitchen,
      List<CodeLabelResponse> laundry,
      List<CodeLabelResponse> livingAmenities,
      List<CodeLabelResponse> securityFeatures,
      List<CommonSpaceResponse> commonSpaces,
      List<CodeLabelResponse> providedSupplies) {}

  /** 공용공간 종류의 code/label과, 데이터가 있는 경우에만 사용하는 개수를 담는다. */
  public record CommonSpaceResponse(CodeLabelResponse type, Integer count) {}

  /**
   * 동일 가격·조건을 공유하는 실제 방 묶음 하나다.
   *
   * <p>프론트는 name을 그대로 표시하고 filterTags의 label을 배지에 표시한다. 필터 요청에는 같은 항목의 code를 사용한다.
   */
  public record RoomOfferResponse(
      String roomOfferId,
      String name,
      Listing.RoomOfferStatus status,
      Listing.Pricing pricing,
      Listing.Inventory inventory,
      List<CodeLabelResponse> filterTags,
      List<String> roomImageUrls) {}

  /** 사용자 언어로 선택된 매물 설명과 번역 범위에서 제외된 기존 추가 안내다. */
  public record DescriptionsResponse(String description, String extraNotes) {}
}
