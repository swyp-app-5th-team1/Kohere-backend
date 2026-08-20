package com.kohere.chat.infrastructure.websocket;

import com.kohere.chat.presentation.stomp.ChatStompDestinations;
import com.kohere.chat.presentation.stomp.dto.ChatControlEventPayload;
import com.kohere.chat.presentation.stomp.dto.ChatControlPingPayload;
import jakarta.validation.Valid;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Controller;

/**
 * 프런트가 개인 control queue 구독을 끝냈는지 확인할 수 있도록 PING에 PONG으로 답한다.
 *
 * <p>WebSocket CONNECT 성공은 통로만 연결됐다는 뜻이다. 앱이 개인 queue를 SUBSCRIBE하기 전에 서버가 응답하면 그 응답을 놓칠 수 있으므로, 앱이
 * 먼저 control queue를 구독하고 이 handler에 ping을 보낸다. 같은 requestId의 PONG을 받으면 개인 응답 통로까지 준비됐다고 판단한다.
 */
@Controller
public class ChatControlMessageController {

  private final ChatSessionMessageSender sessionMessageSender;

  public ChatControlMessageController(ChatSessionMessageSender sessionMessageSender) {
    this.sessionMessageSender = sessionMessageSender;
  }

  /**
   * {@code /app/chat/control/ping} 요청을 보낸 바로 그 WebSocket session에만 PONG을 보낸다.
   *
   * @param ping 프런트가 만든 상관관계 UUID
   * @param headers 1단계 JWT 인증으로 채운 사용자·session 정보
   */
  @MessageMapping(ChatStompDestinations.CONTROL_APPLICATION_DESTINATION)
  public void pong(@Valid @Payload ChatControlPingPayload ping, StompHeaderAccessor headers) {
    // payload에는 userId가 없으며, 이 호출로 CONNECT 인증이 유지되고 있는지 다시 확인한다.
    ChatStompSessionUser.requireUserId(headers);
    String sessionId = ChatStompSessionUser.requireSessionId(headers);

    sessionMessageSender.sendToSession(
        sessionId,
        ChatStompDestinations.CONTROL_USER_DESTINATION,
        ChatControlEventPayload.pong(ping.requestId()));
  }
}
