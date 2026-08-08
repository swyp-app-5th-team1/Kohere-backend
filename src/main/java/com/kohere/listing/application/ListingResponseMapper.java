package com.kohere.listing.application;

import com.kohere.listing.api.ListingCodeLabelView;
import com.kohere.listing.api.RecommendedListingView;
import com.kohere.listing.api.RoomOfferBookingView;
import com.kohere.listing.application.dto.CodeLabelResponse;
import com.kohere.listing.application.dto.FavoriteListingResponse;
import com.kohere.listing.application.dto.ListingDetailResponse;
import com.kohere.listing.application.dto.ListingMapResponse;
import com.kohere.listing.application.dto.ListingSummaryResponse;
import com.kohere.listing.application.dto.RecentListingResponse;
import com.kohere.listing.domain.ConditionTag;
import com.kohere.listing.domain.Listing;
import com.kohere.listing.domain.ListingSearchResult;
import com.kohere.listing.domain.LocalizedText;
import com.kohere.listing.domain.catalog.ListingCatalogCategory;
import com.kohere.listing.domain.favorite.FavoriteListing;
import com.kohere.listing.domain.recent.RecentListingView;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Listing 도메인 모델을 사용자 언어가 적용된 API 응답과 모듈 간 published view로 변환한다. */
final class ListingResponseMapper {

  private ListingResponseMapper() {}

  /** 목록/키워드 검색 화면의 매물 응답을 현재 사용자 언어로 만든다. */
  static ListingSummaryResponse toSummary(
      ListingSearchResult result, Integer distanceMeters, ListingLocalizationContext localization) {
    Listing listing = result.listing();
    return new ListingSummaryResponse(
        listing.getId(),
        localization.text(listing.getTitle()),
        localization.codeLabel(ListingCatalogCategory.LISTING_TYPE, listing.getType()),
        listing.getStatus(),
        localization.codeLabel(ListingCatalogCategory.RENTAL_TYPE, listing.getRentalType()),
        toRefundPolicy(listing, localization),
        listing.getContract(),
        localization.codeLabel(ListingCatalogCategory.GENDER_POLICY, listing.getGenderPolicy()),
        toGeoPoint(listing),
        toAddress(listing, localization),
        toNearestTransit(listing, localization),
        listing.getNearbyUniversityCodes(),
        toBuilding(listing, localization),
        listing.getPropertyPolicies(),
        toFacilities(listing, localization),
        conditionResponses(listing, localization),
        result.roomOffers().stream()
            .map(roomOffer -> toRoomOfferResponse(roomOffer, localization))
            .toList(),
        toDescriptions(listing, localization),
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
   * <p>현재 추천 published API는 아직 code/label 구조를 사용하지 않으므로 기본 영어 문구와 기존 raw code 계약을 유지한다. listing
   * HTTP API의 다국어 응답과 별개의 모듈 간 계약이다.
   */
  static RecommendedListingView toRecommendedView(
      Listing listing, ListingLocalizationContext localization) {
    List<Listing.RoomOffer> activeOffers = activeRoomOffers(listing);
    return new RecommendedListingView(
        listing.getId(),
        localization.text(listing.getTitle()),
        toPublishedCodeLabel(
            localization.codeLabel(ListingCatalogCategory.LISTING_TYPE, listing.getType())),
        minMonthlyRent(activeOffers),
        maxMonthlyRent(activeOffers),
        minDeposit(activeOffers),
        maxDeposit(activeOffers),
        thumbnailUrl(listing),
        listing.getLocation().latitude(),
        listing.getLocation().longitude(),
        conditionResponses(listing, localization).stream()
            .map(ListingResponseMapper::toPublishedCodeLabel)
            .toList());
  }

  /** application 응답 code/label을 모듈 간 공개 타입으로 복사한다. */
  private static ListingCodeLabelView toPublishedCodeLabel(CodeLabelResponse response) {
    return new ListingCodeLabelView(response.code(), response.label());
  }

  /**
   * booking 모듈에 전달할 예약용 방 상품 published view를 만든다.
   *
   * <p>booking 공개 계약에는 사용자 언어 인자가 없으므로 외국인 앱 기본값인 영어를 사용한다.
   */
  static RoomOfferBookingView toBookingView(Listing listing, Listing.RoomOffer offer) {
    return new RoomOfferBookingView(
        listing.getId(),
        offer.roomOfferId(),
        listing.getTitle().resolve(LocalizedText.DEFAULT_LANGUAGE),
        thumbnailUrl(listing),
        listing.getAddress().fullAddress().resolve(LocalizedText.DEFAULT_LANGUAGE),
        offer.name().resolve(LocalizedText.DEFAULT_LANGUAGE),
        offer.pricing().deposit(),
        offer.pricing().monthlyRent(),
        offer.inventory().nextAvailableFrom(),
        listing.getLandlordId());
  }

  /** 지도 SDK가 개별 마커로 사용할 최소 좌표 DTO를 만든다. */
  static ListingMapResponse.Marker toMapMarker(Listing listing) {
    return new ListingMapResponse.Marker(
        listing.getId(), listing.getLocation().latitude(), listing.getLocation().longitude());
  }

  /** 내 찜 목록에서 사용할 매물 응답을 현재 사용자 언어로 만든다. */
  static FavoriteListingResponse toFavoriteListing(
      FavoriteListing favoriteListing, ListingLocalizationContext localization) {
    Listing listing = favoriteListing.listing();
    return new FavoriteListingResponse(
        listing.getId(),
        localization.text(listing.getTitle()),
        localization.codeLabel(ListingCatalogCategory.LISTING_TYPE, listing.getType()),
        listing.getStatus(),
        localization.codeLabel(ListingCatalogCategory.RENTAL_TYPE, listing.getRentalType()),
        toRefundPolicy(listing, localization),
        listing.getContract(),
        localization.codeLabel(ListingCatalogCategory.GENDER_POLICY, listing.getGenderPolicy()),
        toGeoPoint(listing),
        toAddress(listing, localization),
        toNearestTransit(listing, localization),
        listing.getNearbyUniversityCodes(),
        toBuilding(listing, localization),
        listing.getPropertyPolicies(),
        toFacilities(listing, localization),
        conditionResponses(listing, localization),
        activeRoomOfferResponses(listing, localization),
        toDescriptions(listing, localization),
        listing.getImageUrls(),
        true,
        listing.getFavoriteCount(),
        listing.getCreatedAt(),
        listing.getUpdatedAt(),
        favoriteListing.favorite().getFavoritedAt());
  }

  /** 최근 본 매물 응답을 현재 사용자 언어로 만든다. */
  static RecentListingResponse toRecentListing(
      RecentListingView recentListing, boolean favorited, ListingLocalizationContext localization) {
    Listing listing = recentListing.listing();
    return new RecentListingResponse(
        listing.getId(),
        localization.text(listing.getTitle()),
        localization.codeLabel(ListingCatalogCategory.LISTING_TYPE, listing.getType()),
        listing.getStatus(),
        localization.codeLabel(ListingCatalogCategory.RENTAL_TYPE, listing.getRentalType()),
        toRefundPolicy(listing, localization),
        listing.getContract(),
        localization.codeLabel(ListingCatalogCategory.GENDER_POLICY, listing.getGenderPolicy()),
        toGeoPoint(listing),
        toAddress(listing, localization),
        toNearestTransit(listing, localization),
        listing.getNearbyUniversityCodes(),
        toBuilding(listing, localization),
        listing.getPropertyPolicies(),
        toFacilities(listing, localization),
        conditionResponses(listing, localization),
        activeRoomOfferResponses(listing, localization),
        toDescriptions(listing, localization),
        listing.getImageUrls(),
        favorited,
        listing.getFavoriteCount(),
        listing.getCreatedAt(),
        listing.getUpdatedAt(),
        recentListing.recentListing().getViewedAt());
  }

  /** 상세 화면에서 사용할 매물 응답을 현재 사용자 언어로 만든다. */
  static ListingDetailResponse toDetail(
      Listing listing, boolean favorited, ListingLocalizationContext localization) {
    return new ListingDetailResponse(
        listing.getId(),
        localization.text(listing.getTitle()),
        localization.codeLabel(ListingCatalogCategory.LISTING_TYPE, listing.getType()),
        listing.getStatus(),
        localization.codeLabel(ListingCatalogCategory.RENTAL_TYPE, listing.getRentalType()),
        toRefundPolicy(listing, localization),
        listing.getContract(),
        localization.codeLabel(ListingCatalogCategory.GENDER_POLICY, listing.getGenderPolicy()),
        toGeoPoint(listing),
        toAddress(listing, localization),
        toNearestTransit(listing, localization),
        listing.getNearbyUniversityCodes(),
        toBuilding(listing, localization),
        listing.getPropertyPolicies(),
        toFacilities(listing, localization),
        conditionResponses(listing, localization),
        activeRoomOfferResponses(listing, localization),
        toDescriptions(listing, localization),
        listing.getImageUrls(),
        favorited,
        listing.getFavoriteCount(),
        listing.getCreatedAt(),
        listing.getUpdatedAt());
  }

  /** 도메인 좌표를 프론트가 바로 쓰는 lat/lng 응답으로 바꾼다. */
  private static ListingDetailResponse.GeoPoint toGeoPoint(Listing listing) {
    return new ListingDetailResponse.GeoPoint(
        listing.getLocation().latitude(), listing.getLocation().longitude());
  }

  /** 행정 코드는 보존하고 전체/상세 주소만 사용자 언어로 선택한다. */
  private static ListingDetailResponse.AddressResponse toAddress(
      Listing listing, ListingLocalizationContext localization) {
    Listing.Address address = listing.getAddress();
    return new ListingDetailResponse.AddressResponse(
        address.city(),
        address.district(),
        localization.text(address.fullAddress()),
        localization.text(address.detail()));
  }

  /** 가까운 교통수단 코드는 code/label로, 고유 문구는 선택된 언어로 바꾼다. */
  private static ListingDetailResponse.NearestTransitResponse toNearestTransit(
      Listing listing, ListingLocalizationContext localization) {
    Listing.NearestTransit transit = listing.getNearestTransit();
    return new ListingDetailResponse.NearestTransitResponse(
        localization.codeLabel(ListingCatalogCategory.TRANSIT_TYPE, transit.type()),
        localization.text(transit.name()),
        transit.walkMinutes(),
        localization.text(transit.nearbyPlacesDescription()));
  }

  /** 건물 유형만 code/label로 바꾸고 물리 정보는 그대로 보존한다. */
  private static ListingDetailResponse.BuildingResponse toBuilding(
      Listing listing, ListingLocalizationContext localization) {
    Listing.Building building = listing.getBuilding();
    return new ListingDetailResponse.BuildingResponse(
        localization.codeLabel(ListingCatalogCategory.BUILDING_TYPE, building.type()),
        building.usedFloorMin(),
        building.usedFloorMax(),
        building.totalFloors(),
        building.parkingAvailable(),
        building.elevatorAvailable());
  }

  /** 환불 정책의 안정적인 code와 현재 언어의 고유 설명 문장을 조합한다. */
  private static ListingDetailResponse.RefundPolicyResponse toRefundPolicy(
      Listing listing, ListingLocalizationContext localization) {
    return new ListingDetailResponse.RefundPolicyResponse(
        listing.getRefundPolicy().code().name(),
        localization.text(listing.getRefundPolicy().description()));
  }

  /** facilities의 모든 화면 표시 코드를 해당 카테고리의 code/label 응답으로 바꾼다. */
  private static ListingDetailResponse.FacilitiesResponse toFacilities(
      Listing listing, ListingLocalizationContext localization) {
    Listing.Facilities facilities = listing.getFacilities();
    return new ListingDetailResponse.FacilitiesResponse(
        enumCodeLabels(
            facilities.heatingSystem(), ListingCatalogCategory.HEATING_SYSTEM, localization),
        stringCodeLabels(facilities.kitchen(), ListingCatalogCategory.KITCHEN, localization),
        stringCodeLabels(facilities.laundry(), ListingCatalogCategory.LAUNDRY, localization),
        stringCodeLabels(
            facilities.livingAmenities(), ListingCatalogCategory.LIVING_AMENITY, localization),
        stringCodeLabels(
            facilities.securityFeatures(), ListingCatalogCategory.SECURITY_FEATURE, localization),
        facilities.commonSpaces().stream()
            .map(
                space ->
                    new ListingDetailResponse.CommonSpaceResponse(
                        localization.codeLabel(ListingCatalogCategory.COMMON_SPACE, space.type()),
                        space.count()))
            .toList(),
        stringCodeLabels(
            facilities.providedSupplies(), ListingCatalogCategory.PROVIDED_SUPPLY, localization));
  }

  /** 상세 설명은 현재 언어 하나만 선택하고 extraNotes는 기존 요청 범위대로 그대로 둔다. */
  private static ListingDetailResponse.DescriptionsResponse toDescriptions(
      Listing listing, ListingLocalizationContext localization) {
    return new ListingDetailResponse.DescriptionsResponse(
        localization.text(listing.getDescriptions().text()),
        listing.getDescriptions().extraNotes());
  }

  /** 공개 응답에 포함할 ACTIVE 방 상품을 현재 언어 응답으로 바꾼다. */
  private static List<ListingDetailResponse.RoomOfferResponse> activeRoomOfferResponses(
      Listing listing, ListingLocalizationContext localization) {
    return activeRoomOffers(listing).stream()
        .map(roomOffer -> toRoomOfferResponse(roomOffer, localization))
        .toList();
  }

  /** 방 이름을 선택 언어로, filterTags를 code/label 목록으로 바꾼다. */
  private static ListingDetailResponse.RoomOfferResponse toRoomOfferResponse(
      Listing.RoomOffer roomOffer, ListingLocalizationContext localization) {
    return new ListingDetailResponse.RoomOfferResponse(
        roomOffer.roomOfferId(),
        localization.text(roomOffer.name()),
        roomOffer.status(),
        roomOffer.pricing(),
        roomOffer.inventory(),
        enumCodeLabels(roomOffer.filterTags(), ListingCatalogCategory.CONDITION_TAG, localization),
        roomOffer.roomImageUrls());
  }

  /** 매물 카드·상세 조건 합집합을 프론트가 사용할 code/label 목록으로 바꾼다. */
  private static List<CodeLabelResponse> conditionResponses(
      Listing listing, ListingLocalizationContext localization) {
    return enumCodeLabels(
        listingConditions(listing), ListingCatalogCategory.CONDITION_TAG, localization);
  }

  /** enum 코드 집합을 enum 선언 순서가 유지되는 안정적인 code/label 목록으로 바꾼다. */
  private static List<CodeLabelResponse> enumCodeLabels(
      Set<? extends Enum<?>> codes,
      ListingCatalogCategory category,
      ListingLocalizationContext localization) {
    return codes.stream()
        .sorted(Comparator.comparingInt(code -> code.ordinal()))
        .map(code -> localization.codeLabel(category, code))
        .toList();
  }

  /** 문자열 코드 집합을 코드 오름차순의 안정적인 code/label 목록으로 바꾼다. */
  private static List<CodeLabelResponse> stringCodeLabels(
      Set<String> codes, ListingCatalogCategory category, ListingLocalizationContext localization) {
    return codes.stream().sorted().map(code -> localization.codeLabel(category, code)).toList();
  }

  /**
   * ACTIVE 방 상품의 filterTags 합집합과 매물 정책에서 파생되는 NO_ARC 조건을 만든다.
   *
   * <p>이 메서드는 저장 값을 바꾸지 않고 응답에서만 매물 단위 조건을 계산한다.
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

  /** 공개 화면에 노출할 수 있는 ACTIVE 방 상품만 반환한다. */
  private static List<Listing.RoomOffer> activeRoomOffers(Listing listing) {
    return listing.getRoomOffers().stream()
        .filter(offer -> offer.status() == Listing.RoomOfferStatus.ACTIVE)
        .toList();
  }

  /** 추천 카드에 사용할 활성 방 상품 중 가장 낮은 월세다. */
  private static int minMonthlyRent(List<Listing.RoomOffer> offers) {
    return offers.stream().mapToInt(offer -> offer.pricing().monthlyRent()).min().orElseThrow();
  }

  /** 추천 카드에 사용할 활성 방 상품 중 가장 높은 월세다. */
  private static int maxMonthlyRent(List<Listing.RoomOffer> offers) {
    return offers.stream().mapToInt(offer -> offer.pricing().monthlyRent()).max().orElseThrow();
  }

  /** 추천 카드에 사용할 보증금 최저값이다. */
  private static int minDeposit(List<Listing.RoomOffer> offers) {
    return offers.stream().mapToInt(offer -> offer.pricing().deposit()).min().orElseThrow();
  }

  /** 추천 카드에 사용할 보증금 최고값이다. */
  private static int maxDeposit(List<Listing.RoomOffer> offers) {
    return offers.stream().mapToInt(offer -> offer.pricing().deposit()).max().orElseThrow();
  }

  /** 건물 이미지 중 첫 번째 이미지를 썸네일로 사용하고, 없으면 null을 반환한다. */
  private static String thumbnailUrl(Listing listing) {
    return listing.getImageUrls().isEmpty() ? null : listing.getImageUrls().getFirst();
  }
}
