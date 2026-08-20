package com.kohere.chat.presentation.stomp.dto;

/**
 * 서버가 발행하는 채팅 STOMP 이벤트 종류다.
 *
 * <p>프런트엔드는 destination만으로 payload 의미를 추측하지 않고 이 값을 함께 확인한다. 문자열 code는 와이어 계약이므로 이름 변경은 호환성 변경이다.
 */
public enum ChatStompEventType {
  /** 원문 TEXT 또는 서버 생성 BOOKING_CARD가 MySQL에 저장됨. */
  MESSAGE_CREATED,
  /** 특정 수신자를 위한 번역 처리가 최종 상태에 도달함. */
  MESSAGE_TRANSLATION_UPDATED,
  /** 사용자 목록에 새 채팅방이 생김. */
  ROOM_CREATED,
  /** 기존 채팅방의 마지막 메시지 등 목록 정보가 바뀜. */
  ROOM_UPDATED,
  /** 새 활동으로 사용자가 숨긴 방이 다시 표시됨. 과거 이력 복원을 뜻하지 않음. */
  ROOM_REOPENED,
  /** control ping을 해당 session이 정상 수신·응답함. */
  PONG,
  /** room topic 구독 등록과 high-watermark 계산이 끝남. */
  SUBSCRIPTION_READY
}
