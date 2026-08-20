package com.kohere.booking.application;

import com.kohere.booking.BookingCreatedEvent;
import com.kohere.booking.domain.Booking;
import com.kohere.listing.api.RoomOfferBookingView;
import com.kohere.user.api.ApplicantProfileView;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 저장된 예약과 이미 검증한 매물·신청자 정보를 {@link BookingCreatedEvent} 한 건으로 조립한다.
 *
 * <p>이 조립을 {@link BookingService} 안에 길게 작성하지 않는 이유는 예약 저장 흐름을 읽을 때 검증·저장·이벤트 발행의 큰 단계가 먼저 보이게 하기
 * 위해서다. 카드 금액 계산도 한곳에서 수행해 예약 상세와 신청 카드가 서로 다른 공식을 사용하지 않게 한다.
 */
@Component
public class BookingCreatedEventFactory {

  /**
   * 예약 성공 시점의 BOOKING_CARD 사본을 만든다.
   *
   * @param booking ID가 발급된 저장 완료 예약
   * @param offer 예약 생성 전에 공개 상태와 소유자를 검증한 객실 상품 정보
   * @param applicant 신청자 본인의 현재 프로필 정보
   * @return Event Publication Registry에 기록할 모듈 이벤트
   */
  public BookingCreatedEvent create(
      Booking booking, RoomOfferBookingView offer, ApplicantProfileView applicant) {
    Objects.requireNonNull(booking, "booking is required");
    Objects.requireNonNull(offer, "offer is required");
    Objects.requireNonNull(applicant, "applicant is required");

    int contractPeriod = booking.getContractPeriod();
    int totalAmount = offer.deposit() + offer.monthlyRent() * contractPeriod;

    BookingCreatedEvent.ListingSnapshot listing =
        new BookingCreatedEvent.ListingSnapshot(
            offer.listingId(),
            offer.thumbnailUrl(),
            offer.title(),
            offer.address(),
            offer.monthlyRent());
    BookingCreatedEvent.ApplicantSnapshot applicantSnapshot =
        new BookingCreatedEvent.ApplicantSnapshot(
            applicant.userId(),
            applicant.name(),
            applicant.gender(),
            applicant.country(),
            applicant.countryName(),
            applicant.email());
    BookingCreatedEvent.CardSnapshot card =
        new BookingCreatedEvent.CardSnapshot(
            listing,
            applicantSnapshot,
            booking.getRoomOfferId(),
            offer.roomOfferName(),
            booking.getMoveInDate(),
            contractPeriod,
            offer.deposit(),
            totalAmount);

    return new BookingCreatedEvent(
        UUID.randomUUID(),
        booking.getId(),
        booking.getListingId(),
        booking.getTenantId(),
        booking.getLandlordId(),
        booking.getCreatedAt(),
        card);
  }
}
