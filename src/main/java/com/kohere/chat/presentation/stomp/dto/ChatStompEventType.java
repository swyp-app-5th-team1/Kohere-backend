package com.kohere.chat.presentation.stomp.dto;

/** 서버가 발행하는 채팅 STOMP 이벤트의 종류. */
public enum ChatStompEventType {
  MESSAGE_CREATED,
  MESSAGE_TRANSLATION_UPDATED,
  ROOM_CREATED,
  ROOM_UPDATED,
  ROOM_REOPENED,
  PONG,
  SUBSCRIPTION_READY
}
