package com.kohere.booking;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 예약 생성 도메인 이벤트. booking 모듈이 발행하고 chat 모듈이 같은 1:1 채팅방에 신청 카드를 저장한다.
 *
 * <p>이 이벤트는 예약과 같은 MySQL 트랜잭션에서 Spring Modulith Event Publication Registry에 기록된다. HTTP 요청이 끝난 뒤 채팅
 * 처리가 비동기로 실행되므로, 서버가 중간에 종료돼도 미완료 publication을 다음 기동 때 다시 처리할 수 있다.
 *
 * <p>채팅 listener가 나중에 매물이나 사용자 원본을 다시 조회하면 신청 직후 값이 바뀌거나 삭제됐을 때 카드 내용이 달라질 수 있다. 그래서 신청 화면을 재현하는 데
 * 필요한 값은 예약이 성공한 시점에 {@link CardSnapshot}으로 고정한다. 이 사본은 이벤트 전달용일 뿐이며 최종 보관 정본은 {@code
 * chat_messages.payload}다.
 *
 * @param eventId 로그에서 동일 이벤트 처리를 추적하기 위한 UUID
 * @param bookingId 생성된 예약의 MySQL ID이며 카드 중복 방지 키로 사용
 * @param listingId 신청한 매물 ID
 * @param tenantId 신청한 임차인의 users.id
 * @param landlordId 매물 소유자인 임대인의 users.id
 * @param occurredAt 예약이 실제 저장된 UTC 시각
 * @param cardSnapshot 신청 당시 카드 표시 정보
 */
public record BookingCreatedEvent(
    UUID eventId,
    Long bookingId,
    String listingId,
    Long tenantId,
    Long landlordId,
    Instant occurredAt,
    CardSnapshot cardSnapshot) {

  /**
   * BOOKING_CARD 한 장을 만들기 위한 신청 시점 사본이다.
   *
   * @param listing 매물 제목·주소·대표 이미지·월세 사본
   * @param applicant 신청자 표시 정보 사본
   * @param roomOfferId 신청한 객실 상품 ID
   * @param roomOfferName 신청한 객실 상품 이름
   * @param moveInDate 입주 희망일
   * @param contractPeriod 희망 계약 개월 수
   * @param deposit 신청 당시 보증금
   * @param totalAmount 보증금과 계약 기간 월세를 합한 총 초기 비용
   */
  public record CardSnapshot(
      ListingSnapshot listing,
      ApplicantSnapshot applicant,
      String roomOfferId,
      String roomOfferName,
      LocalDate moveInDate,
      int contractPeriod,
      int deposit,
      int totalAmount) {}

  /** 신청 카드에 표시할 매물 정보를 예약 시점 값으로 고정한 사본이다. */
  public record ListingSnapshot(
      String listingId, String thumbnailUrl, String title, String address, int monthlyRent) {}

  /** 임대인용 신청 카드에 표시할 신청자 정보를 예약 시점 값으로 고정한 사본이다. */
  public record ApplicantSnapshot(
      Long userId, String name, String gender, String country, String countryName, String email) {}
}
