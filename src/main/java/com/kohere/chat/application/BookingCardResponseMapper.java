package com.kohere.chat.application;

import com.kohere.chat.application.dto.BookingCardApplicantResponse;
import com.kohere.chat.application.dto.BookingCardListingResponse;
import com.kohere.chat.application.dto.BookingCardResponse;
import com.kohere.chat.domain.BookingCardPayload;

/** BOOKING_CARD의 DB 저장용 payload를 REST와 STOMP가 함께 사용하는 응답 DTO로 바꾼다. */
public final class BookingCardResponseMapper {

  /** 상태가 없는 변환 도구이므로 객체를 만들지 않고 정적 메서드만 사용한다. */
  private BookingCardResponseMapper() {}

  /**
   * 신청 당시 저장한 카드 사본을 프런트엔드 응답 모양으로 옮긴다.
   *
   * <p>REST 메시지 이력과 실시간 STOMP 이벤트가 이 메서드를 함께 사용하므로, 두 경로에서 카드 필드 이름이나 값이 달라지는 일을 막는다.
   */
  public static BookingCardResponse toResponse(BookingCardPayload payload) {
    BookingCardPayload.Listing listing = payload.listing();
    BookingCardPayload.Applicant applicant = payload.applicant();

    return new BookingCardResponse(
        payload.bookingId(),
        new BookingCardListingResponse(
            listing.listingId(),
            listing.thumbnailUrl(),
            listing.title(),
            listing.address(),
            listing.monthlyRent()),
        new BookingCardApplicantResponse(
            applicant.userId(),
            applicant.name(),
            applicant.gender(),
            applicant.country(),
            applicant.countryName(),
            applicant.email()),
        payload.roomOfferId(),
        payload.roomOfferName(),
        payload.moveInDate(),
        payload.contractPeriod(),
        payload.deposit(),
        payload.totalAmount());
  }
}
