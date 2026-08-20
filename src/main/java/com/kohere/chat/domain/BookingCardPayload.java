package com.kohere.chat.domain;

import java.time.LocalDate;

/**
 * 신청 완료 시 서버가 {@link MessageType#BOOKING_CARD} 메시지에 고정하는 구조화 데이터다.
 *
 * <p>이 값은 Google 번역 대상이 아니며 앱이 로그인 사용자의 역할에 맞춰 같은 데이터를 다른 카드 UI로 배치한다. 신청 이후 매물 가격이나 사용자 프로필이 바뀌어도
 * 당시 신청 내용을 재현해야 하므로 메시지 JSON에 스냅샷으로 보존한다.
 *
 * @param bookingId 카드의 원본 신청 번호
 * @param listing 신청 당시 매물 표시·가격 정보
 * @param applicant 신청 당시 임차인 표시 정보
 * @param roomOfferId 신청한 객실 상품 번호
 * @param roomOfferName 신청한 객실 상품 표시 이름
 * @param moveInDate 입주 희망일
 * @param contractPeriod 희망 계약 개월 수
 * @param deposit 신청 당시 보증금
 * @param totalAmount 신청 당시 계산된 총 초기 비용
 */
public record BookingCardPayload(
    Long bookingId,
    Listing listing,
    Applicant applicant,
    String roomOfferId,
    String roomOfferName,
    LocalDate moveInDate,
    int contractPeriod,
    int deposit,
    int totalAmount) {

  /**
   * 신청 카드 안에 저장하는 매물 스냅샷이다.
   *
   * @param listingId 원본 매물 번호
   * @param thumbnailUrl 신청 당시 대표 이미지 URL, 이미지가 없으면 {@code null}
   * @param title 신청 당시 매물 제목
   * @param address 신청 당시 표시 주소
   * @param monthlyRent 신청 당시 월 임대료
   */
  public record Listing(
      String listingId, String thumbnailUrl, String title, String address, int monthlyRent) {}

  /**
   * 임대인 카드에 표시할 신청자 스냅샷이다.
   *
   * <p>사용자 탈퇴 시 ADR-0014에 따라 이름·성별·국가·이메일은 후속 익명화 처리로 비워질 수 있다.
   *
   * @param userId 신청자 사용자 번호
   * @param name 신청 당시 이름
   * @param gender 신청 당시 성별 code
   * @param country 신청 당시 국가 code
   * @param countryName 신청 당시 국가 표시 이름
   * @param email 신청 당시 이메일
   */
  public record Applicant(
      Long userId, String name, String gender, String country, String countryName, String email) {}
}
