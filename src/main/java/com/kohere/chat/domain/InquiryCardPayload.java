package com.kohere.chat.domain;

import java.util.Objects;

/**
 * 문의하기로 새 채팅방을 만들 때 서버가 {@link MessageType#INQUIRY_CARD}에 저장하는 매물 요약이다.
 *
 * <p>프런트는 이 값을 보내지 않고 {@code listingId}만 보낸다. 서버가 공개 매물에서 조회한 값을 문의 시점의 사본으로 저장하므로, 매물 정보가 나중에 바뀌어도
 * 기존 채팅방에서는 문의를 시작했을 당시의 카드를 그대로 보여 줄 수 있다.
 *
 * @param listingId 원본 매물 번호이자 View Detail 이동에 사용할 값
 * @param thumbnailUrl 문의 당시 첫 번째 대표 이미지 URL, 이미지가 없으면 {@code null}
 * @param title 문의 당시 매물 제목
 * @param city 주소의 언어 무관 city code
 * @param district 주소의 언어 무관 district code
 * @param listingType 고시원·코리빙·쉐어하우스를 구분하는 매물 유형 code
 * @param monthlyRentMin 문의 당시 ACTIVE 방 상품 중 가장 낮은 월세(KRW)
 * @param monthlyRentMax 문의 당시 ACTIVE 방 상품 중 가장 높은 월세(KRW)
 */
public record InquiryCardPayload(
    String listingId,
    String thumbnailUrl,
    String title,
    String city,
    String district,
    String listingType,
    int monthlyRentMin,
    int monthlyRentMax) {

  /** 필수 표시값과 월세 범위를 생성 시점에 검증해 잘못된 문의서가 DB까지 전달되지 않게 한다. */
  public InquiryCardPayload {
    requireText(listingId, "listingId");
    requireText(title, "title");
    requireText(city, "city");
    requireText(district, "district");
    requireText(listingType, "listingType");

    if (monthlyRentMin < 0 || monthlyRentMax < monthlyRentMin) {
      throw new IllegalArgumentException("INQUIRY_CARD monthly rent range is invalid");
    }
  }

  /** null·공백 문자열을 같은 방식으로 검사해 생성자 검증을 읽기 쉽게 유지한다. */
  private static void requireText(String value, String field) {
    Objects.requireNonNull(value, field + " is required");
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }
}
