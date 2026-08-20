package com.kohere.chat.presentation.stomp;

/**
 * 프런트엔드와 서버가 공유하는 STOMP endpoint·destination 정본이다.
 *
 * <p>문자열을 여러 handler와 테스트에 직접 복사하면 한쪽만 바뀌어 연결은 되지만 메시지가 오지 않는 오류가 생길 수 있어 한곳에 모은다. 이 클래스는 경로 계약만
 * 정의하며 WebSocket 설정·인가·메시지 처리 코드가 모두 이 값을 재사용한다.
 */
public final class ChatStompDestinations {

  /** 최초 WebSocket transport 연결에 사용하는 HTTP handshake endpoint. */
  public static final String HANDSHAKE_ENDPOINT = "/ws/chat";

  /** 프런트엔드가 TEXT를 보내는 application destination. {@code roomId}는 payload가 아니라 경로에서 얻는다. */
  public static final String MESSAGE_SEND = "/app/chat-rooms/{roomId}/messages";

  /** {@code @MessageMapping}이 application prefix를 제거한 뒤 사용하는 TEXT handler 경로. */
  public static final String MESSAGE_APPLICATION_DESTINATION = "/chat-rooms/{roomId}/messages";

  /** MySQL 저장을 마친 원문 또는 서버 생성 카드를 방 참여자에게 알리는 topic. */
  public static final String ROOM_TOPIC = "/topic/chat-rooms/{roomId}";

  /** 원래 TEXT SEND를 보낸 session만 구독하는 애플리케이션 저장 결과 queue. */
  public static final String ACK_QUEUE = "/user/queue/chat-acks";

  /** 서버가 {@link #ACK_QUEUE} 구독자에게 값을 보낼 때 사용하는 user destination. */
  public static final String ACK_USER_DESTINATION = "/queue/chat-acks";

  /** 개별 TEXT SEND의 검증·권한 오류를 원래 발신 session에만 보내는 queue. */
  public static final String ERROR_QUEUE = "/user/queue/chat-errors";

  /** 서버가 {@link #ERROR_QUEUE} 구독자에게 값을 보낼 때 사용하는 user destination. */
  public static final String ERROR_USER_DESTINATION = "/queue/chat-errors";

  /** 방 생성·갱신·재노출을 알려 목록 REST 재조회 시점을 제공하는 개인 queue. */
  public static final String ROOM_EVENT_QUEUE = "/user/queue/chat-room-events";

  /** 서버가 {@link #ROOM_EVENT_QUEUE} 구독자에게 값을 보낼 때 사용하는 user destination. */
  public static final String ROOM_EVENT_USER_DESTINATION = "/queue/chat-room-events";

  /** 원문과 분리된 사용자별 번역 최종 결과를 수신자에게만 보내는 개인 queue. */
  public static final String TRANSLATION_QUEUE = "/user/queue/chat-translations";

  /** 서버가 {@link #TRANSLATION_QUEUE} 구독자에게 값을 보낼 때 사용하는 user destination. */
  public static final String TRANSLATION_USER_DESTINATION = "/queue/chat-translations";

  /** {@code @MessageMapping}이 application prefix 제거 뒤 사용하는 control ping 경로. */
  public static final String CONTROL_APPLICATION_DESTINATION = "/chat/control/ping";

  /** 개인 queue 준비 여부를 확인하는 프런트용 application-level ping destination. */
  public static final String CONTROL_SEND = "/app" + CONTROL_APPLICATION_DESTINATION;

  /** PONG과 방 구독 high-watermark를 원래 session에 돌려주는 개인 control queue. */
  public static final String CONTROL_QUEUE = "/user/queue/chat-control";

  /** 서버가 {@link #CONTROL_QUEUE} 구독자에게 값을 보낼 때 사용하는 user destination. */
  public static final String CONTROL_USER_DESTINATION = "/queue/chat-control";

  /** 구체적인 room topic 문자열을 만들 때 사용하는 고정 앞부분이다. */
  public static final String ROOM_TOPIC_PREFIX = "/topic/chat-rooms/";

  /** 클라이언트 TEXT SEND 전체 경로를 엄격하게 해석할 때 사용하는 앞부분이다. */
  public static final String MESSAGE_SEND_PREFIX = "/app/chat-rooms/";

  /**
   * 서버 채팅방 번호를 프런트가 실제로 구독할 topic 문자열로 바꾼다.
   *
   * <p>경로 조립 코드를 여러 곳에서 직접 작성하면 슬래시 하나 때문에 실시간 수신이 조용히 실패할 수 있어 이 메서드만 사용한다.
   *
   * @param roomId 서버가 발급한 양의 채팅방 번호
   * @return 예: {@code /topic/chat-rooms/42}
   */
  public static String roomTopic(long roomId) {
    if (roomId < 1) {
      throw new IllegalArgumentException("roomId must be positive");
    }
    return ROOM_TOPIC_PREFIX + roomId;
  }

  /**
   * 서버 채팅방 번호를 프런트가 실제로 SEND할 application destination으로 바꾼다.
   *
   * @param roomId 서버가 발급한 양의 채팅방 번호
   * @return 예: {@code /app/chat-rooms/42/messages}
   */
  public static String messageSend(long roomId) {
    if (roomId < 1) {
      throw new IllegalArgumentException("roomId must be positive");
    }
    return MESSAGE_SEND_PREFIX + roomId + "/messages";
  }

  private ChatStompDestinations() {}
}
