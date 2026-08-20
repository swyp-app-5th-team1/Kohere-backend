package com.kohere.chat.infrastructure.websocket;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;

/**
 * Spring의 실제 WebSocket 연결 시작·종료를 {@link ChatWebSocketSessionManager}에 알려 주는 연결 고리다.
 *
 * <p>STOMP interceptor는 논리 frame을 보지만 underlying socket 객체를 직접 갖지 않는다. 반대로 이 decorator는 socket을 알지만
 * JWT header는 모른다. 둘을 같은 session ID로 이어야 토큰 만료 시 실제 연결을 닫을 수 있다.
 */
@Component
@RequiredArgsConstructor
public class ChatWebSocketSessionDecoratorFactory implements WebSocketHandlerDecoratorFactory {

  private final ChatWebSocketSessionManager sessionManager;

  /** 기존 Spring handler를 감싸되 메시지 처리 자체는 그대로 위임한다. */
  @Override
  public WebSocketHandler decorate(WebSocketHandler handler) {
    return new WebSocketHandlerDecorator(handler) {

      /** handshake가 끝난 실제 session을 먼저 등록하고 기존 연결 처리로 넘긴다. */
      @Override
      public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessionManager.register(session);
        try {
          super.afterConnectionEstablished(session);
        } catch (Exception e) {
          // 하위 handler 초기화가 실패하면 close callback이 오지 않을 수도 있으므로 예약 작업을 즉시 정리한다.
          sessionManager.unregister(session.getId());
          throw e;
        }
      }

      /** 정상 종료·오류 종료 모두 예약 작업이 남지 않도록 finally에서 정리한다. */
      @Override
      public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus)
          throws Exception {
        try {
          super.afterConnectionClosed(session, closeStatus);
        } finally {
          sessionManager.unregister(session.getId());
        }
      }
    };
  }
}
