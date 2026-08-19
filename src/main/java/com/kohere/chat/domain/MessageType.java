package com.kohere.chat.domain;

/** 메시지 타입. 사용자는 {@link #TEXT}만 보내며 {@link #BOOKING_CARD}는 신청 완료 후 서버가 생성한다. */
public enum MessageType {
  TEXT,
  BOOKING_CARD
}
