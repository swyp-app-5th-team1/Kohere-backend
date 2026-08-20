package com.kohere.chat.application.dto;

/**
 * 신청이 완료된 시점의 신청자 표시 정보다.
 *
 * <p>신청 카드는 일반 텍스트처럼 프런트엔드가 만드는 메시지가 아니라 서버가 {@code BookingCreatedEvent}를 처리해 생성한다. 이후 사용자가 프로필을
 * 바꾸더라도 당시 접수 내용을 설명할 수 있도록, 화면에 필요한 값을 카드 payload에 사본으로 보관한다.
 */
public record BookingCardApplicantResponse(
    /** 신청한 사용자의 서버 식별자. 프런트엔드가 지정할 수 없다. */
    Long userId,
    /** 신청 시점의 표시 이름. */
    String name,
    /** 신청 시점의 성별 code. 번역할 문구가 아니라 안정적인 code로 저장한다. */
    String gender,
    /** 신청 시점의 ISO 국가 code. */
    String country,
    /** 신청 시점에 화면에 표시한 국가명. */
    String countryName,
    /** 신청 시점의 연락 이메일. */
    String email) {}
