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
    LocationInfo locationInfo,
    PropertyInfo propertyInfo,
    List<RoomOfferResponse> roomOffers,
    ContentInfo content,
    InteractionInfo interaction,
    Instant createdAt,
    Instant updatedAt) {

  /** 제목·유형·공개 상태처럼 상세 화면 상단에서 쓰는 기본 정보다. */
  public record BasicInfo(String title, ListingType type, Listing.ListingStatus status) {}

  /** 지도 좌표, 주소, 주변 시설/학교처럼 위치 기반 표시에 쓰는 정보다. */
  public record LocationInfo(
      GeoPoint location,
      Listing.Address address,
      Listing.NearestTransit nearestTransit,
      String nearbyPlacesDescription,
      Set<String> nearbyUniversityCodes) {}

  /** 프론트 지도 컴포넌트에서 바로 쓰기 쉬운 위도·경도 값이다. */
  public record GeoPoint(double lat, double lng) {}

  /** 건물 자체의 시설·정책과 활성 방 상품들의 특징 합집합이다. */
  public record PropertyInfo(
      Listing.Building building,
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

  /** 상세 설명, 이미지, 썸네일처럼 콘텐츠 렌더링에 쓰는 정보다. */
  public record ContentInfo(
      Listing.Descriptions descriptions,
      String extraNotes,
      List<String> imageUrls,
      String thumbnailUrl) {}

  /** 현재 사용자와 매물의 상호작용 상태다. 찜 기능 연결 전에는 favorited=false로 내려간다. */
  public record InteractionInfo(boolean favorited, int favoriteCount) {}
}
