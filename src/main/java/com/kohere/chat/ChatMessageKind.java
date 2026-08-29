package com.kohere.chat;

/** 알림 후보 이벤트가 어떤 채팅 메시지 저장에서 시작됐는지 나타내는 chat 모듈의 공개 값이다. */
public enum ChatMessageKind {
  /** 사용자가 STOMP로 보낸 일반 채팅 원문이다. */
  TEXT,

  /** 임차인의 문의하기로 서버가 생성한 문의 카드다. */
  INQUIRY_CARD,

  /** 입주 신청 완료 뒤 서버가 생성한 신청 카드다. */
  BOOKING_CARD
}
