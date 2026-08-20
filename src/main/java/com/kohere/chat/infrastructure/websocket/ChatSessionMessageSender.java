package com.kohere.chat.infrastructure.websocket;

import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * STOMP 응답을 같은 계정의 모든 기기가 아니라 요청을 보낸 WebSocket session 하나에만 전달한다.
 *
 * <p>한 사용자가 휴대전화와 태블릿에서 동시에 접속할 수 있다. {@code convertAndSendToUser}에 사용자 번호만 주면 두 기기 모두 응답을 받을 수
 * 있으므로, Spring header에 원래 sessionId를 함께 넣어 PONG·구독 준비 응답·후속 ACK와 오류가 올바른 socket으로만 가게 한다.
 */
@Component
public class ChatSessionMessageSender {

  private final ObjectProvider<SimpMessagingTemplate> messagingTemplateProvider;

  /**
   * WebSocket 설정이 만들어지는 도중 template을 즉시 요구하면 Spring 설정 빈 사이에 순환 생성이 생긴다. 실제 응답을 보낼 때 꺼내도록 provider로
   * 지연한다.
   */
  public ChatSessionMessageSender(ObjectProvider<SimpMessagingTemplate> messagingTemplateProvider) {
    this.messagingTemplateProvider = messagingTemplateProvider;
  }

  /**
   * 지정한 사용자의 특정 session에 payload를 보낸다.
   *
   * @param sessionId 원래 요청을 보낸 WebSocket session 번호
   * @param userDestination 서버 내부용 {@code /queue/...} destination
   * @param payload JSON으로 변환할 응답 객체
   */
  public void sendToSession(String sessionId, String userDestination, Object payload) {
    if (sessionId == null || sessionId.isBlank()) {
      throw new IllegalArgumentException("sessionId is required");
    }
    if (userDestination == null || !userDestination.startsWith("/queue/")) {
      throw new IllegalArgumentException("userDestination must start with /queue/");
    }
    Objects.requireNonNull(payload, "payload is required");

    SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
    // DefaultUserDestinationResolver가 같은 사용자의 여러 session 중 이 session 하나만 고르는 기준이다.
    headers.setSessionId(sessionId);
    headers.setLeaveMutable(true);

    /*
     * 여기서 user 인자에도 sessionId를 넣는 것은 Spring이 지원하는 '특정 session 전용 user destination' 규칙이다.
     * 사용자 번호를 넣으면 요청 socket이 이미 끊긴 순간 같은 계정의 다른 기기로 fallback할 수 있으므로 사용하지 않는다.
     */
    messagingTemplateProvider
        .getObject()
        .convertAndSendToUser(sessionId, userDestination, payload, headers.getMessageHeaders());
  }
}
