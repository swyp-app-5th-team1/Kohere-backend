package com.kohere.listing.application;

import com.kohere.listing.api.RecommendedListingView;
import com.kohere.listing.api.RoomOfferBookingView;
import com.kohere.listing.application.dto.FavoriteListingResponse;
import com.kohere.listing.application.dto.ListingDetailResponse;
import com.kohere.listing.application.dto.ListingMapResponse;
import com.kohere.listing.application.dto.ListingSummaryResponse;
import com.kohere.listing.application.dto.RecentListingResponse;
import com.kohere.listing.domain.ConditionTag;
import com.kohere.listing.domain.FavoriteListing;
import com.kohere.listing.domain.Listing;
import com.kohere.listing.domain.ListingSearchResult;
import com.kohere.listing.domain.RecentListingView;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Listing 도메인 모델을 클라이언트 응답 DTO와 모듈 간 published view로 변환한다. */
final class ListingResponseMapper {

  private ListingResponseMapper() {}

  /**
   * 목록/키워드 검색 화면의 매물 응답을 만든다.
   *
   * <p>저장소는 이미 Listing 단위로 중복을 제거하고, 그 매물 안에서 검색 조건을 통과한 활성 {@code roomOffers}만 담아 넘긴다. 응답은 상세 조회와
   * 같은 public listing 문서 구조를 유지하되, 거리 계산 결과만 목록/검색 맥락의 계산값으로 덧붙인다.
   */
  static ListingSummaryResponse toSummary(ListingSearchResult result, Integer distanceMeters) {
    Listing listing = result.listing();
    return new ListingSummaryResponse(
        listing.getId(),
        listing.getTitle(),
        listing.getType(),
        listing.getStatus(),
        listing.getRentalType(),
        listing.getRefundPolicy(),
        listing.getContract(),
        listing.getGenderPolicy(),
        toGeoPoint(listing),
        listing.getAddress(),
        listing.getNearestTransit(),
        listing.getNearbyUniversityCodes(),
        listing.getBuilding(),
        listing.getPropertyPolicies(),
        listing.getFacilities(),
        listingConditions(listing),
        result.roomOffers().stream().map(ListingResponseMapper::toRoomOfferResponse).toList(),
        listing.getDescriptions(),
        listing.getImageUrls(),
        distanceMeters,
        false,
        listing.getFavoriteCount(),
        listing.getCreatedAt(),
        listing.getUpdatedAt());
  }

  /**
   * diagnosis 모듈에 전달할 추천 매물 published view를 만든다.
   *
   * <p>추천 조회는 방 상품이 아니라 매물 단위 카드로 내려가므로, 월세는 단일 값이 아니라 활성 방 상품 전체의 최저~최고 범위({@code
   * monthlyRentMin}/{@code monthlyRentMax})로 집계한다. 보증금도 같은 규칙으로 {@code minDeposit}/{@code
   * maxDeposit} 범위를 내려준다. 조건 태그는 추천 카드의 Property Details 배지에 바로 쓸 수 있도록 매물의 활성 방 상품 전체 기준으로 계산한다.
   */
  static RecommendedListingView toRecommendedView(Listing listing) {
    List<Listing.RoomOffer> activeOffers = activeRoomOffers(listing);
    return new RecommendedListingView(
        listing.getId(),
        listing.getTitle(),
        listing.getType().name(),
        minMonthlyRent(activeOffers),
        maxMonthlyRent(activeOffers),
        minDeposit(activeOffers),
        maxDeposit(activeOffers),
        thumbnailUrl(listing),
        listing.getLocation().latitude(),
        listing.getLocation().longitude(),
        listingConditions(listing).stream().map(Enum::name).toList());
  }

  /**
   * booking 모듈에 전달할 예약용 방 상품 published view를 만든다.
   *
   * <p>이미 공개(PUBLISHED)·활성(ACTIVE)으로 선별된 매물·방 상품을 받아 요약·가격·입주 가능일을 원시 타입으로 조립한다(내부 도메인 타입 미공유).
   */
  static RoomOfferBookingView toBookingView(Listing listing, Listing.RoomOffer offer) {
    return new RoomOfferBookingView(
        listing.getId(),
        offer.roomOfferId(),
        listing.getTitle(),
        thumbnailUrl(listing),
        listing.getAddress().fullAddress(),
        offer.name(),
        offer.pricing().deposit(),
        offer.pricing().monthlyRent(),
        offer.inventory().nextAvailableFrom());
  }

  /** 지도 SDK가 개별 마커로 사용할 최소 좌표 DTO를 만든다. */
  static ListingMapResponse.Marker toMapMarker(Listing listing) {
    return new ListingMapResponse.Marker(
        listing.getId(), listing.getLocation().latitude(), listing.getLocation().longitude());
  }

  /**
   * 내 찜 목록에서 사용할 매물 응답 DTO를 만든다.
   *
   * <p>목록 항목은 이미 "내가 찜한 매물"이라는 전제에서 조회되므로 {@code favorited=true}로 고정한다. 공개 API에 내려줄 수 있는 listing 문서
   * 필드는 상세 조회와 같은 형태로 유지하고, 찜 목록 전용 계산값인 {@code favoritedAt}만 마지막에 덧붙인다.
   */
  static FavoriteListingResponse toFavoriteListing(FavoriteListing favoriteListing) {
    Listing listing = favoriteListing.listing();
    return new FavoriteListingResponse(
        listing.getId(),
        listing.getTitle(),
        listing.getType(),
        listing.getStatus(),
        listing.getRentalType(),
        listing.getRefundPolicy(),
        listing.getContract(),
        listing.getGenderPolicy(),
        toGeoPoint(listing),
        listing.getAddress(),
        listing.getNearestTransit(),
        listing.getNearbyUniversityCodes(),
        listing.getBuilding(),
        listing.getPropertyPolicies(),
        listing.getFacilities(),
        listingConditions(listing),
        activeRoomOfferResponses(listing),
        listing.getDescriptions(),
        listing.getImageUrls(),
        true,
        listing.getFavoriteCount(),
        listing.getCreatedAt(),
        listing.getUpdatedAt(),
        favoriteListing.favorite().getFavoritedAt());
  }

  /**
   * 최근 본 매물 화면에서 사용할 매물 응답을 만든다.
   *
   * <p>최근 본 목록은 검색 필터가 없으므로 공개 매물 안의 활성 방 상품 전체를 내려준다. {@code favorited}는 목록 조회 시
   * FavoriteRepository에서 한 번에 계산한 사용자별 찜 여부이며, {@code viewedAt}만 최근 본 목록 전용 필드다.
   */
  static RecentListingResponse toRecentListing(RecentListingView recentListing, boolean favorited) {
    Listing listing = recentListing.listing();
    return new RecentListingResponse(
        listing.getId(),
        listing.getTitle(),
        listing.getType(),
        listing.getStatus(),
        listing.getRentalType(),
        listing.getRefundPolicy(),
        listing.getContract(),
        listing.getGenderPolicy(),
        toGeoPoint(listing),
        listing.getAddress(),
        listing.getNearestTransit(),
        listing.getNearbyUniversityCodes(),
        listing.getBuilding(),
        listing.getPropertyPolicies(),
        listing.getFacilities(),
        listingConditions(listing),
        activeRoomOfferResponses(listing),
        listing.getDescriptions(),
        listing.getImageUrls(),
        favorited,
        listing.getFavoriteCount(),
        listing.getCreatedAt(),
        listing.getUpdatedAt(),
        recentListing.recentListing().getViewedAt());
  }

  /** 상세 화면에서 사용할 매물 상세 DTO를 만든다. */
  static ListingDetailResponse toDetail(Listing listing) {
    return toDetail(listing, false);
  }

  /**
   * 상세 화면에서 사용할 매물 상세 DTO를 만든다.
   *
   * <p>응답 필드 배치는 MongoDB v2 저장 구조에 가깝게 유지한다. 다만 공개 상세 화면에 노출할 수 있도록 {@code roomOffers[]}는 ACTIVE
   * 항목만 내려주고, 현재 사용자 기준 찜 여부({@code favorited})만 계산값으로 추가한다.
   */
  static ListingDetailResponse toDetail(Listing listing, boolean favorited) {
    List<Listing.RoomOffer> activeOffers = activeRoomOffers(listing);
    return new ListingDetailResponse(
        listing.getId(),
        listing.getTitle(),
        listing.getType(),
        listing.getStatus(),
        listing.getRentalType(),
        listing.getRefundPolicy(),
        listing.getContract(),
        listing.getGenderPolicy(),
        toGeoPoint(listing),
        listing.getAddress(),
        listing.getNearestTransit(),
        listing.getNearbyUniversityCodes(),
        listing.getBuilding(),
        listing.getPropertyPolicies(),
        listing.getFacilities(),
        listingConditions(listing),
        activeOffers.stream().map(ListingResponseMapper::toRoomOfferResponse).toList(),
        listing.getDescriptions(),
        listing.getImageUrls(),
        favorited,
        listing.getFavoriteCount(),
        listing.getCreatedAt(),
        listing.getUpdatedAt());
  }

  private static ListingDetailResponse.GeoPoint toGeoPoint(Listing listing) {
    return new ListingDetailResponse.GeoPoint(
        listing.getLocation().latitude(), listing.getLocation().longitude());
  }

  private static List<ListingDetailResponse.RoomOfferResponse> activeRoomOfferResponses(
      Listing listing) {
    return activeRoomOffers(listing).stream()
        .map(ListingResponseMapper::toRoomOfferResponse)
        .toList();
  }

  /**
   * 매물 카드와 상세의 Property Details 배지에 사용할 매물 단위 조건 목록을 만든다.
   *
   * <p>{@code roomOffers[].filterTags}는 방 타입마다 다른 조건이다. 프론트가 매물 카드나 상세 상단에서 "이 매물이 제공하는 조건"을 한 줄로
   * 보여줄 때는 활성 방 상품 전체의 태그 합집합이 필요하다. 그래서 INACTIVE 방은 제외하고 ACTIVE 방의 태그만 합친다.
   *
   * <p>{@code NO_ARC}는 방 상품 태그가 아니라 매물 정책({@code propertyPolicies.arcRequired=false})에서 파생되는 조건이다.
   * 따라서 방 태그 합집합을 만든 뒤 ARC가 필요 없는 매물이면 별도로 {@code NO_ARC}를 추가한다.
   */
  private static Set<ConditionTag> listingConditions(Listing listing) {
    EnumSet<ConditionTag> conditions = EnumSet.noneOf(ConditionTag.class);
    activeRoomOffers(listing).stream()
        .map(Listing.RoomOffer::filterTags)
        .forEach(conditions::addAll);
    if (!listing.getPropertyPolicies().arcRequired()) {
      conditions.add(ConditionTag.NO_ARC);
    }
    return Collections.unmodifiableSet(conditions);
  }

  /** 방 상품 하나를 상세 응답의 roomOffers 항목으로 변환한다. */
  private static ListingDetailResponse.RoomOfferResponse toRoomOfferResponse(
      Listing.RoomOffer roomOffer) {
    return new ListingDetailResponse.RoomOfferResponse(
        roomOffer.roomOfferId(),
        roomOffer.name(),
        roomOffer.status(),
        roomOffer.pricing(),
        roomOffer.inventory(),
        roomOffer.filterTags(),
        roomOffer.roomImageUrls());
  }

  /**
   * 필터 없는 공개 응답에서 내려줄 활성 방 상품만 추린다.
   *
   * <p>찜 목록과 최근 본 매물은 검색 조건이 없으므로, 공개 화면에 노출 가능한 ACTIVE 방 상품 전체를 내려준다.
   */
  private static List<Listing.RoomOffer> activeRoomOffers(Listing listing) {
    return listing.getRoomOffers().stream()
        .filter(offer -> offer.status() == Listing.RoomOfferStatus.ACTIVE)
        .toList();
  }

  /** 추천 published view에서 쓸 활성 방 상품 중 가장 낮은 월세다. */
  private static int minMonthlyRent(List<Listing.RoomOffer> offers) {
    return offers.stream().mapToInt(offer -> offer.pricing().monthlyRent()).min().orElseThrow();
  }

  /** 추천 published view에서 쓸 활성 방 상품 중 가장 높은 월세다. */
  private static int maxMonthlyRent(List<Listing.RoomOffer> offers) {
    return offers.stream().mapToInt(offer -> offer.pricing().monthlyRent()).max().orElseThrow();
  }

  /** 조건을 통과한 방 상품들의 보증금 최저값이다. */
  private static int minDeposit(List<Listing.RoomOffer> offers) {
    return offers.stream().mapToInt(offer -> offer.pricing().deposit()).min().orElseThrow();
  }

  /** 조건을 통과한 방 상품들의 보증금 최고값이다. */
  private static int maxDeposit(List<Listing.RoomOffer> offers) {
    return offers.stream().mapToInt(offer -> offer.pricing().deposit()).max().orElseThrow();
  }

  /** 건물 이미지 중 첫 번째 이미지를 썸네일로 사용하고, 없으면 null을 반환한다. */
  private static String thumbnailUrl(Listing listing) {
    return listing.getImageUrls().isEmpty() ? null : listing.getImageUrls().getFirst();
  }
}
