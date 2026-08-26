package com.kohere.listing.application;

import com.kohere.listing.api.BookingListingQueryService;
import com.kohere.listing.api.RoomOfferBookingView;
import com.kohere.listing.domain.Listing;
import com.kohere.listing.domain.ListingRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * {@link BookingListingQueryService} 구현. 공개(PUBLISHED) 매물의 활성(ACTIVE) 방 상품을 {@code (listingId,
 * roomOfferId)}로 조회해 {@link RoomOfferBookingView}로 매핑한다 — booking이 예약 생성·조회 시점에 매물 요약·가격·입주 가능일을
 * 실시간 조인하는 데 쓴다(스냅샷 없음, ADR-0002 공개 API 협력).
 *
 * <p>매물 부재·비공개(PUBLISHED 아님)·방 상품 부재/비활성이면 {@code Optional.empty()}를 반환하며(잘못된 ObjectId도 포함 — {@link
 * ListingRepository#findById}가 빈 값 처리), 호출측(booking)이 이를 404 {@code LISTING_NOT_FOUND}로 처리한다. 이 쿼리
 * 자체는 not-found로 예외를 던지지 않는다.
 */
@Service
@RequiredArgsConstructor
public class BookingListingQueryServiceImpl implements BookingListingQueryService {

  private final ListingRepository listingRepository;

  @Override
  public Optional<RoomOfferBookingView> findPublishedRoomOffer(
      String listingId, String roomOfferId) {
    return listingRepository
        .findById(listingId)
        .filter(listing -> listing.getStatus() == Listing.ListingStatus.PUBLISHED)
        .flatMap(
            listing ->
                activeRoomOffer(listing, roomOfferId)
                    .map(offer -> ListingResponseMapper.toBookingView(listing, offer)));
  }

  @Override
  public boolean hasPublishedActiveRoomOffer(String listingId) {
    return listingRepository
        .findById(listingId)
        .filter(listing -> listing.getStatus() == Listing.ListingStatus.PUBLISHED)
        .map(
            listing ->
                listing.getRoomOffers().stream()
                    .anyMatch(offer -> offer.status() == Listing.RoomOfferStatus.ACTIVE))
        .orElse(false);
  }

  @Override
  public Optional<RoomOfferBookingView> findRoomOfferForExistingBooking(
      String listingId, String roomOfferId) {
    return listingRepository
        .findById(listingId)
        .flatMap(
            listing ->
                roomOffer(listing, roomOfferId)
                    .map(offer -> ListingResponseMapper.toBookingView(listing, offer)));
  }

  /** 상태를 가리지 않고 {@code roomOfferId}가 일치하는 방을 고른다. 표시 전용 경로만 쓴다. */
  private static Optional<Listing.RoomOffer> roomOffer(Listing listing, String roomOfferId) {
    return listing.getRoomOffers().stream()
        .filter(offer -> offer.roomOfferId().equals(roomOfferId))
        .findFirst();
  }

  /** 매물의 방 상품 중 {@code roomOfferId}가 일치하고 상태가 ACTIVE인 것을 고른다(없으면 빈 값). */
  private static Optional<Listing.RoomOffer> activeRoomOffer(Listing listing, String roomOfferId) {
    return listing.getRoomOffers().stream()
        .filter(
            offer ->
                offer.roomOfferId().equals(roomOfferId)
                    && offer.status() == Listing.RoomOfferStatus.ACTIVE)
        .findFirst();
  }
}
