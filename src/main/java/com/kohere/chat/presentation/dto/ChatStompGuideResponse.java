package com.kohere.chat.presentation.dto;

import com.kohere.chat.domain.Message;
import com.kohere.chat.presentation.stomp.ChatStompDestinations;

/**
 * 프런트엔드가 실시간 채팅에 연결할 때 필요한 STOMP 경로와 제한값을 한 번에 확인하는 안내 응답이다.
 *
 * <p>이 값들은 WebSocket 연결을 대신 실행하지 않는다. Swagger에서 연결 계약을 쉽게 찾고, 프런트 코드에 경로를 잘못 복사하는 일을 줄이기 위한 읽기 전용
 * 정보다. 실제 연결은 앱이 {@link ChatStompDestinations#HANDSHAKE_ENDPOINT}로 WebSocket을 연 뒤 STOMP CONNECT를
 * 보내서 시작한다.
 */
public record ChatStompGuideResponse(
    /** 모든 환경에서 사용하는 WebSocket handshake 상대 경로. */
    String webSocketEndpoint,
    /** 개발 서버에 배포된 백엔드로 연결할 때 사용하는 전체 WSS 주소. */
    String developmentWebSocketUrl,
    /** 로컬 백엔드를 8080 포트로 실행했을 때 사용하는 전체 WS 주소. */
    String localWebSocketUrl,
    /** STOMP CONNECT native header에 넣을 JWT 헤더 이름. */
    String connectHeaderName,
    /** 실제 access token으로 중괄호 부분을 바꿔 보내는 헤더 값 형식. */
    String connectHeaderValueFormat,
    /** 개인 queue 수신 여부를 PING/PONG으로 확인하는 SEND 경로. */
    String controlSendDestination,
    /** 특정 채팅방의 TEXT와 BOOKING_CARD를 실시간 수신하는 구독 경로 형식. */
    String roomSubscribeDestination,
    /** 특정 채팅방에 사용자 TEXT를 보내는 SEND 경로 형식. */
    String messageSendDestination,
    /** PONG과 SUBSCRIPTION_READY를 현재 사용자 session에만 전달하는 개인 queue. */
    String controlQueue,
    /** 내가 보낸 TEXT가 MySQL에 저장된 결과를 받는 개인 queue. */
    String ackQueue,
    /** 내가 보낸 TEXT의 검증·권한 오류를 받는 개인 queue. */
    String errorQueue,
    /** 채팅방 생성·갱신·재표시 신호를 받는 개인 queue. */
    String roomEventQueue,
    /** 자동 번역이 구현된 뒤 사용자별 번역 결과를 받을 예약 queue. */
    String translationQueue,
    /** 사용자 TEXT 한 건에 허용되는 최대 Unicode 문자 수. */
    int maxTextCodePoints,
    /** 연결 생존 여부를 확인하는 STOMP heartbeat 간격(초). */
    int heartbeatSeconds) {

  private static final String DEVELOPMENT_WEBSOCKET_URL = "wss://dev.kohere.app/ws/chat";
  private static final String LOCAL_WEBSOCKET_URL = "ws://localhost:8080/ws/chat";
  private static final int HEARTBEAT_SECONDS = 10;

  /**
   * 현재 서버와 프런트가 함께 사용해야 하는 STOMP 계약을 만든다.
   *
   * <p>destination을 직접 다시 적지 않고 실제 WebSocket 처리 코드가 사용하는 상수를 재사용한다. 서버 경로가 바뀌었는데 Swagger 안내만 예전 경로로
   * 남는 문제를 막기 위해서다.
   *
   * @return 현재 실시간 채팅 연결 안내
   */
  public static ChatStompGuideResponse currentContract() {
    return new ChatStompGuideResponse(
        ChatStompDestinations.HANDSHAKE_ENDPOINT,
        DEVELOPMENT_WEBSOCKET_URL,
        LOCAL_WEBSOCKET_URL,
        "Authorization",
        "Bearer {accessToken}",
        ChatStompDestinations.CONTROL_SEND,
        ChatStompDestinations.ROOM_TOPIC,
        ChatStompDestinations.MESSAGE_SEND,
        ChatStompDestinations.CONTROL_QUEUE,
        ChatStompDestinations.ACK_QUEUE,
        ChatStompDestinations.ERROR_QUEUE,
        ChatStompDestinations.ROOM_EVENT_QUEUE,
        ChatStompDestinations.TRANSLATION_QUEUE,
        Message.MAX_TEXT_CODE_POINTS,
        HEARTBEAT_SECONDS);
  }
}
