package com.kohere.chat.application;

import com.kohere.chat.application.dto.InquiryCardResponse;
import com.kohere.chat.domain.InquiryCardPayload;

/** INQUIRY_CARD의 DB 저장용 payload를 REST와 STOMP가 함께 사용하는 응답 DTO로 바꾼다. */
public final class InquiryCardResponseMapper {

  /** 상태가 없는 변환 도구이므로 객체를 만들지 않고 정적 메서드만 사용한다. */
  private InquiryCardResponseMapper() {}

  /**
   * 문의 당시 저장한 매물 사본을 프런트엔드 응답 모양으로 옮긴다.
   *
   * <p>REST 메시지 이력과 실시간 STOMP 이벤트가 이 메서드를 함께 사용하므로 두 경로의 필드 이름과 값이 달라지지 않는다.
   *
   * @param payload MySQL JSON 컬럼에서 읽은 문의서 사본
   * @return REST와 STOMP가 공통으로 사용할 문의서 응답
   */
  public static InquiryCardResponse toResponse(InquiryCardPayload payload) {
    return new InquiryCardResponse(
        payload.listingId(),
        payload.thumbnailUrl(),
        payload.title(),
        payload.city(),
        payload.district(),
        payload.listingType(),
        payload.monthlyRentMin(),
        payload.monthlyRentMax());
  }
}
