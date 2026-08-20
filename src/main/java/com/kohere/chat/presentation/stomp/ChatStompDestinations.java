package com.kohere.chat.presentation.stomp;

/**
 * 프런트엔드와 서버가 공유하는 STOMP endpoint·destination 정본이다.
 *
 * <p>문자열을 여러 handler와 테스트에 직접 복사하면 한쪽만 바뀌어 연결은 되지만 메시지가 오지 않는 오류가 생길 수 있어 한곳에 모은다. 이 클래스는 경로 계약만
 * 정의하며 실제 WebSocket 설정·인가·메시지 처리는 후속 단계가 담당한다.
 */
public final class ChatStompDestinations {

  /** 최초 WebSocket transport 연결에 사용하는 HTTP handshake endpoint. */
  public static final String HANDSHAKE_ENDPOINT = "/ws/chat";

  /** 프런트엔드가 TEXT를 보내는 application destination. {@code roomId}는 payload가 아니라 경로에서 얻는다. */
  public static final String MESSAGE_SEND = "/app/chat-rooms/{roomId}/messages";

  /** MySQL 저장을 마친 원문 또는 서버 생성 카드를 방 참여자에게 알리는 topic. */
  public static final String ROOM_TOPIC = "/topic/chat-rooms/{roomId}";

  /** 원래 TEXT SEND를 보낸 session만 구독하는 애플리케이션 저장 결과 queue. */
  public static final String ACK_QUEUE = "/user/queue/chat-acks";

  /** 개별 TEXT SEND의 검증·권한 오류를 원래 발신 session에만 보내는 queue. */
  public static final String ERROR_QUEUE = "/user/queue/chat-errors";

  /** 방 생성·갱신·재노출을 알려 목록 REST 재조회 시점을 제공하는 개인 queue. */
  public static final String ROOM_EVENT_QUEUE = "/user/queue/chat-room-events";

  /** 원문과 분리된 사용자별 번역 최종 결과를 수신자에게만 보내는 개인 queue. */
  public static final String TRANSLATION_QUEUE = "/user/queue/chat-translations";

  /** 개인 queue 준비 여부를 확인하는 application-level ping destination. */
  public static final String CONTROL_SEND = "/app/chat/control/ping";

  /** PONG과 방 구독 high-watermark를 원래 session에 돌려주는 개인 control queue. */
  public static final String CONTROL_QUEUE = "/user/queue/chat-control";

  private ChatStompDestinations() {}
}
