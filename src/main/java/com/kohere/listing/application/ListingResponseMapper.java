package com.kohere.listing.application;

import com.kohere.listing.api.RecommendedListingView;
import com.kohere.listing.application.dto.FavoriteListingResponse;
import com.kohere.listing.application.dto.ListingDetailResponse;
import com.kohere.listing.application.dto.ListingMapResponse;
import com.kohere.listing.application.dto.ListingSummaryResponse;
import com.kohere.listing.domain.FavoriteListing;
import com.kohere.listing.domain.Listing;
import com.kohere.listing.domain.ListingSearchResult;
import java.util.Comparator;
import java.util.List;

/** Listing 도메인 모델을 외부 응답 DTO와 published view로 변환한다. */
final class ListingResponseMapper {

  private ListingResponseMapper() {}

  /**
   * 목록 화면의 카드 응답을 만든다.
   *
   * <p>목록 조회는 이미 저장소에서 조건에 맞는 {@code roomOffer}를 모두 펼쳐 {@link ListingSearchResult}로 넘겨준다. 따라서 여기서는
   * 추가로 대표 방을 고르지 않고, 전달받은 방 상품의 가격·조건·재고를 그대로 카드에 담는다.
   */
  static ListingSummaryResponse toSummary(ListingSearchResult result, Integer distanceMeters) {
    return toSummary(result.listing(), result.roomOffer(), distanceMeters);
  }

  /** 이미 고른 방 상품으로 목록 응답을 조립한다. */
  private static ListingSummaryResponse toSummary(
      Listing listing, Listing.RoomOffer offer, Integer distanceMeters) {
    return new ListingSummaryResponse(
        listing.getId(),
        offer.roomOfferId(),
        offer.name(),
        listing.getTitle(),
        listing.getType(),
        offer.pricing().monthlyRent(),
        offer.pricing().deposit(),
        offer.pricing().maintenanceFee(),
        offer.inventory().availableCount(),
        thumbnailUrl(listing),
        listing.getLocation().latitude(),
        listing.getLocation().longitude(),
        listing.getAddress().fullAddress(),
        List.copyOf(offer.filterTags()),
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

  /** 상세 화면에서 객체별 섹션으로 렌더링할 수 있게 상세 DTO를 만든다. */
  static ListingDetailResponse toDetail(Listing listing) {
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
        new ListingDetailResponse.InteractionInfo(false, listing.getFavoriteCount()),
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

  /** 건물 이미지 중 첫 번째 이미지를 썸네일로 사용하고, 없으면 null을 반환한다. */
  private static String thumbnailUrl(Listing listing) {
    return listing.getImageUrls().isEmpty() ? null : listing.getImageUrls().getFirst();
  }
}
