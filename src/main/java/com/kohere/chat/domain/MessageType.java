package com.kohere.chat.domain;

/**
 * 채팅방의 메시지 종류다.
 *
 * <p>사용자가 STOMP로 보낼 수 있는 종류는 {@link #TEXT}뿐이다. {@link #INQUIRY_CARD}는 새 문의 채팅방을 만들 때, {@link
 * #BOOKING_CARD}는 입주 신청이 완료될 때 서버가 생성한다.
 */
public enum MessageType {
  TEXT,
  INQUIRY_CARD,
  BOOKING_CARD
}
