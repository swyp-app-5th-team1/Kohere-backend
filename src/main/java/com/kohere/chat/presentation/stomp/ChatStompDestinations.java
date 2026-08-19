package com.kohere.chat.presentation.stomp;

/** 프런트와 서버가 공유하는 STOMP endpoint와 destination 계약. */
public final class ChatStompDestinations {

  public static final String HANDSHAKE_ENDPOINT = "/ws/chat";
  public static final String MESSAGE_SEND = "/app/chat-rooms/{roomId}/messages";
  public static final String ROOM_TOPIC = "/topic/chat-rooms/{roomId}";
  public static final String ACK_QUEUE = "/user/queue/chat-acks";
  public static final String ERROR_QUEUE = "/user/queue/chat-errors";
  public static final String ROOM_EVENT_QUEUE = "/user/queue/chat-room-events";
  public static final String TRANSLATION_QUEUE = "/user/queue/chat-translations";
  public static final String CONTROL_SEND = "/app/chat/control/ping";
  public static final String CONTROL_QUEUE = "/user/queue/chat-control";

  private ChatStompDestinations() {}
}
