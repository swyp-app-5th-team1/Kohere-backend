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
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Listing 도메인 모델을 클라이언트 응답 DTO와 모듈 간 published view로 변환한다. */
final class ListingResponseMapper {

  private ListingResponseMapper() {}

  /**
   * 목록/키워드 검색 화면의 매물 카드 응답을 만든다.
   *
   * <p>저장소는 이미 Listing 단위로 중복을 제거하고, 그 매물 안에서 검색 조건을 통과한 활성 {@code roomOffers}만 담아 넘긴다. 이 메서드는 프론트
   * 카드가 바로 범위 문구(예: 38~40만원/mo)를 그릴 수 있도록 그 방 상품 목록의 가격·보증금·관리비·계약기간을 집계한다.
   */
  static ListingSummaryResponse toSummary(ListingSearchResult result, Integer distanceMeters) {
    Listing listing = result.listing();
    List<Listing.RoomOffer> offers = result.roomOffers();
    return new ListingSummaryResponse(
        listing.getId(),
        listing.getTitle(),
        listing.getType(),
        minMonthlyRent(offers),
        maxMonthlyRent(offers),
        minDeposit(offers),
        maxDeposit(offers),
        minMaintenanceFee(offers),
        maxMaintenanceFee(offers),
        totalAvailableCount(offers),
        minStayMonths(offers),
        maxStayMonths(offers),
        thumbnailUrl(listing),
        listing.getLocation().latitude(),
        listing.getLocation().longitude(),
        listing.getAddress().fullAddress(),
        toNearestTransitSummary(listing.getNearestTransit()),
        aggregateConditionTags(offers),
        distanceMeters,
        false,
        listing.getFavoriteCount());
  }

  /** diagnosis 모듈에 전달할 추천 매물 published view를 만든다. */
  static RecommendedListingView toRecommendedView(Listing listing) {
    Listing.RoomOffer offer = representativeOffer(listing);
    return new RecommendedListingView(
        listing.getId(),
        listing.getTitle(),
        listing.getType().name(),
        offer.pricing().monthlyRent(),
        offer.pricing().deposit(),
        thumbnailUrl(listing),
        listing.getLocation().latitude(),
        listing.getLocation().longitude(),
        offer.filterTags().stream().map(Enum::name).toList());
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
   * 내 찜 목록에서 사용할 매물 요약 DTO를 만든다.
   *
   * <p>목록 항목은 이미 "내가 찜한 매물"이라는 전제에서 조회되므로 {@code favorited=true}로 고정한다. 찜 목록 화면은 거리 기준 정렬/표시를 쓰지
   * 않으므로 일반 {@link ListingSummaryResponse} 대신 {@code favoritedAt}을 포함한 전용 DTO를 반환한다.
   */
  static FavoriteListingResponse toFavoriteListing(FavoriteListing favoriteListing) {
    Listing listing = favoriteListing.listing();
    Listing.RoomOffer offer = representativeOffer(listing);
    return new FavoriteListingResponse(
        listing.getId(),
        listing.getTitle(),
        listing.getType(),
        offer.pricing().monthlyRent(),
        offer.pricing().deposit(),
        offer.pricing().maintenanceFee(),
        thumbnailUrl(listing),
        listing.getLocation().latitude(),
        listing.getLocation().longitude(),
        listing.getAddress().fullAddress(),
        List.copyOf(offer.filterTags()),
        true,
        listing.getFavoriteCount(),
        favoriteListing.favorite().getFavoritedAt());
  }

  /**
   * 최근 본 매물 화면에서 사용할 카드 응답을 만든다.
   *
   * <p>최근 본 목록은 검색 필터가 없으므로, 공개 매물 안의 활성 방 상품 전체를 집계해 일반 매물 리스트와 같은 범위 필드를 만든다. {@code favorited}는
   * 목록 조회 시 FavoriteRepository에서 한 번에 계산한 사용자별 찜 여부다.
   */
  static RecentListingResponse toRecentListing(RecentListingView recentListing, boolean favorited) {
    Listing listing = recentListing.listing();
    List<Listing.RoomOffer> offers = activeRoomOffers(listing);
    return new RecentListingResponse(
        listing.getId(),
        listing.getTitle(),
        listing.getType(),
        minMonthlyRent(offers),
        maxMonthlyRent(offers),
        minDeposit(offers),
        maxDeposit(offers),
        minMaintenanceFee(offers),
        maxMaintenanceFee(offers),
        minStayMonths(offers),
        maxStayMonths(offers),
        thumbnailUrl(listing),
        listing.getLocation().latitude(),
        listing.getLocation().longitude(),
        listing.getAddress().fullAddress(),
        toNearestTransitSummary(listing.getNearestTransit()),
        aggregateConditionTags(offers),
        null,
        favorited,
        listing.getFavoriteCount(),
        recentListing.recentListing().getViewedAt());
  }

  /** 상세 화면에서 객체별 섹션으로 렌더링할 수 있게 상세 DTO를 만든다. */
  static ListingDetailResponse toDetail(Listing listing) {
    return toDetail(listing, false);
  }

  /**
   * 상세 화면에서 객체별 섹션으로 렌더링할 수 있게 상세 DTO를 만든다.
   *
   * <p>상세 API는 로그인 필수이므로 {@code favorited}에는 현재 사용자 기준 실제 찜 여부를 넣는다. 매물 전체 찜 수는 Listing의 비정규화 캐시인
   * {@code favoriteCount}를 그대로 사용한다.
   */
  static ListingDetailResponse toDetail(Listing listing, boolean favorited) {
    return new ListingDetailResponse(
        listing.getId(),
        new ListingDetailResponse.BasicInfo(
            listing.getTitle(), listing.getType(), listing.getStatus()),
        new ListingDetailResponse.LocationInfo(
            new ListingDetailResponse.GeoPoint(
                listing.getLocation().latitude(), listing.getLocation().longitude()),
            listing.getAddress(),
            listing.getNearestTransit(),
            listing.getNearbyPlacesDescription(),
            listing.getNearbyUniversityCodes()),
        new ListingDetailResponse.PropertyInfo(
            listing.getBuilding(),
            listing.getPropertyPolicies(),
            listing.getFacilities(),
            listing.getFeatureSummary()),
        listing.getRoomOffers().stream().map(ListingResponseMapper::toRoomOfferResponse).toList(),
        new ListingDetailResponse.ContentInfo(
            listing.getDescriptions(),
            listing.getExtraNotes(),
            listing.getImageUrls(),
            thumbnailUrl(listing)),
        new ListingDetailResponse.InteractionInfo(favorited, listing.getFavoriteCount()),
        listing.getCreatedAt(),
        listing.getUpdatedAt());
  }

  /** 방 상품 하나를 상세 응답의 roomOffers 항목으로 변환한다. */
  private static ListingDetailResponse.RoomOfferResponse toRoomOfferResponse(
      Listing.RoomOffer roomOffer) {
    return new ListingDetailResponse.RoomOfferResponse(
        roomOffer.roomOfferId(),
        roomOffer.name(),
        roomOffer.status(),
        roomOffer.rentalType(),
        roomOffer.pricing(),
        new ListingDetailResponse.ContractResponse(
            roomOffer.contract().minStayMonths(),
            roomOffer.contract().maxStayMonths(),
            roomOffer.contract().refundPolicy()),
        roomOffer.inventory(),
        roomOffer.genderPolicy(),
        roomOffer.features(),
        roomOffer.filterTags(),
        roomOffer.roomImageUrls());
  }

  /**
   * 매물당 카드가 하나만 필요한 흐름에서 사용할 기본 방 상품을 고른다.
   *
   * <p>일반 목록 조회는 더 이상 이 메서드를 쓰지 않는다. 목록은 조건에 맞는 모든 roomOffer를 카드로 펼친다. 이 메서드는 아직 roomOffer 단위로 바뀌지
   * 않은 찜 목록이나 진단 추천 published view의 기본 가격을 만들 때만 사용한다.
   */
  private static Listing.RoomOffer representativeOffer(Listing listing) {
    return listing.getRoomOffers().stream()
        .filter(offer -> offer.status() == Listing.RoomOfferStatus.ACTIVE)
        .min(Comparator.comparingInt(offer -> offer.pricing().monthlyRent()))
        .orElseGet(() -> listing.getRoomOffers().getFirst());
  }

  /**
   * 필터 없는 카드 목록에서 집계할 활성 방 상품만 추린다.
   *
   * <p>최근 본 매물은 검색 조건이 없지만 일반 목록 카드와 같은 가격 범위를 보여줘야 한다. 따라서 비활성 방 상품은 제외하고, 조회 저장소가 이미 보장한 "활성 방
   * 상품이 최소 1개 있다"는 전제 아래 범위 값을 계산한다.
   */
  private static List<Listing.RoomOffer> activeRoomOffers(Listing listing) {
    return listing.getRoomOffers().stream()
        .filter(offer -> offer.status() == Listing.RoomOfferStatus.ACTIVE)
        .toList();
  }

  /** 조건을 통과한 방 상품 중 가장 낮은 월세를 카드의 월세 범위 시작값으로 쓴다. */
  private static int minMonthlyRent(List<Listing.RoomOffer> offers) {
    return offers.stream().mapToInt(offer -> offer.pricing().monthlyRent()).min().orElseThrow();
  }

  /** 조건을 통과한 방 상품 중 가장 높은 월세를 카드의 월세 범위 끝값으로 쓴다. */
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

  /** 조건을 통과한 방 상품들의 관리비 최저값이다. */
  private static int minMaintenanceFee(List<Listing.RoomOffer> offers) {
    return offers.stream().mapToInt(offer -> offer.pricing().maintenanceFee()).min().orElseThrow();
  }

  /** 조건을 통과한 방 상품들의 관리비 최고값이다. */
  private static int maxMaintenanceFee(List<Listing.RoomOffer> offers) {
    return offers.stream().mapToInt(offer -> offer.pricing().maintenanceFee()).max().orElseThrow();
  }

  /** 목록 카드의 계약 가능 수량은 조건을 통과한 모든 방 상품 묶음의 availableCount 합계다. */
  private static int totalAvailableCount(List<Listing.RoomOffer> offers) {
    return offers.stream().mapToInt(offer -> offer.inventory().availableCount()).sum();
  }

  /** 조건을 통과한 방 상품 중 가장 짧은 최소 계약기간이다. */
  private static int minStayMonths(List<Listing.RoomOffer> offers) {
    return offers.stream().mapToInt(offer -> offer.contract().minStayMonths()).min().orElseThrow();
  }

  /** 조건을 통과한 방 상품 중 가장 긴 최대 계약기간이다. */
  private static int maxStayMonths(List<Listing.RoomOffer> offers) {
    return offers.stream().mapToInt(offer -> offer.contract().maxStayMonths()).max().orElseThrow();
  }

  /**
   * 매물 카드의 조건 태그는 조건을 통과한 방 상품들의 합집합이다.
   *
   * <p>{@link EnumSet}을 사용해 enum 선언 순서를 유지하므로, 같은 데이터는 항상 같은 순서로 직렬화된다.
   */
  private static List<ConditionTag> aggregateConditionTags(List<Listing.RoomOffer> offers) {
    Set<ConditionTag> tags = EnumSet.noneOf(ConditionTag.class);
    offers.forEach(offer -> tags.addAll(offer.filterTags()));
    return List.copyOf(tags);
  }

  /** 건물 이미지 중 첫 번째 이미지를 썸네일로 사용하고, 없으면 null을 반환한다. */
  private static String thumbnailUrl(Listing listing) {
    return listing.getImageUrls().isEmpty() ? null : listing.getImageUrls().getFirst();
  }

  /** 목록 카드에서 사용할 가까운 교통수단 요약을 만든다. */
  private static ListingSummaryResponse.NearestTransitSummary toNearestTransitSummary(
      Listing.NearestTransit transit) {
    if (transit == null) {
      return null;
    }
    return new ListingSummaryResponse.NearestTransitSummary(
        transit.type(), transit.name(), transit.walkMinutes());
  }
}
