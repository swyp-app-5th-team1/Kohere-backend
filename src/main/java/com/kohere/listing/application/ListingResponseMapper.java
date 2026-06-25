package com.kohere.listing.application;

import com.kohere.listing.api.RecommendedListingView;
import com.kohere.listing.application.dto.ListingDetailResponse;
import com.kohere.listing.application.dto.ListingSummaryResponse;
import com.kohere.listing.domain.Listing;
import java.util.Comparator;
import java.util.List;

/** Listing 도메인 모델을 외부 응답 DTO와 published view로 변환한다. */
final class ListingResponseMapper {

  private ListingResponseMapper() {}

  /** 목록 화면에 필요한 대표 가격·좌표·태그만 추려 요약 DTO를 만든다. */
  static ListingSummaryResponse toSummary(Listing listing) {
    Listing.RoomOffer offer = representativeOffer(listing);
    return new ListingSummaryResponse(
        listing.getId(),
        listing.getTitle(),
        listing.getType(),
        offer.pricing().monthlyRent(),
        offer.pricing().deposit(),
        thumbnailUrl(listing),
        listing.getLocation().latitude(),
        listing.getLocation().longitude(),
        List.copyOf(offer.filterTags()),
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

  /** 목록·추천 카드에서 보여줄 대표 방 상품을 월세가 가장 낮은 활성 상품으로 선택한다. */
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
