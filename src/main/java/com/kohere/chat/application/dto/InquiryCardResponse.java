package com.kohere.chat.application.dto;

/**
 * 프런트엔드가 매물 문의서 UI를 그릴 때 사용하는 응답이다.
 *
 * <p>city·district·listingType은 언어가 바뀌어도 같은 code를 반환한다. 앱은 현재 언어가 {@code ko}인지 {@code en}인지에 따라 해당
 * code의 표시 문구만 바꾼다.
 *
 * @param listingId View Detail로 매물 상세 화면을 열 때 사용할 원본 매물 ID
 * @param thumbnailUrl 문의 당시 대표 이미지 URL, 이미지가 없으면 {@code null}
 * @param title 문의 당시 매물 제목
 * @param city 주소의 city code
 * @param district 주소의 district code
 * @param listingType 매물 유형 code
 * @param monthlyRentMin ACTIVE 방 상품의 최소 월세(KRW)
 * @param monthlyRentMax ACTIVE 방 상품의 최대 월세(KRW)
 */
public record InquiryCardResponse(
    String listingId,
    String thumbnailUrl,
    String title,
    String city,
    String district,
    String listingType,
    int monthlyRentMin,
    int monthlyRentMax) {}
