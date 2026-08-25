package com.kohere.listing.api;

import java.util.Optional;

/**
 * 예약(booking) 협력용 매물 공개 쿼리. 공개(PUBLISHED) 매물의 활성(ACTIVE) 방 상품을 {@code (listingId, roomOfferId)}로
 * 조회한다. 결과가 없으면(매물/방 상품 부재·비공개) 빈 값을 반환하며, 호출측(booking)이 404 {@code LISTING_NOT_FOUND}로 처리한다.
 *
 * <p>booking이 조회 시점에 매물 요약·가격·입주 가능일을 실시간 조인하는 데 쓴다(스냅샷 없음 · ADR-0002 공개 API 협력).
 */
public interface BookingListingQueryService {

  Optional<RoomOfferBookingView> findPublishedRoomOffer(String listingId, String roomOfferId);

  /**
   * <b>이미 존재하는 예약</b>의 표시용 조회다. 매물 상태와 방 상품 상태를 <b>둘 다 보지 않는다</b>.
   *
   * <p>예약 카드의 매물명·사진·금액은 예약 문서에 없고 매번 여기로 물어본다. 공개 매물만 대상으로 하면 임대인이 매물을 고치는 동안(수정 심사)이나 방을 내린 뒤에
   * <b>이미 성사된 예약의 카드가 빈 값과 0원으로</b> 렌더된다 — 예외도 404도 아닌 조용한 소실이다. 예약은 매물 상태가 바뀌어도 취소되지 않으므로 그 값은 계속
   * 정확해야 한다.
   *
   * <p>노출이 넓어지지는 않는다. 이 메서드는 매물을 <b>찾는</b> 데 쓸 수 없고 {@code (listingId, roomOfferId)}를 이미 쥐고 있어야
   * 하는데, 그 출처는 요청자에게 스코프된 예약 행뿐이다. 돌려주는 값도 그 사람이 예약할 때 이미 본 것이다.
   *
   * <p><b>예약 생성에는 쓰지 않는다.</b> 생성은 {@link #findPublishedRoomOffer}를 그대로 써서 공개 매물의 활성 방에만 허용된다.
   */
  Optional<RoomOfferBookingView> findRoomOfferForExistingBooking(
      String listingId, String roomOfferId);
}
