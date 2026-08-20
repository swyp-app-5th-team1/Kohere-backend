package com.kohere.chat.infrastructure.websocket;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.kohere.chat.presentation.stomp.ChatStompDestinations;
import com.kohere.chat.presentation.stomp.dto.ChatControlEventPayload;
import com.kohere.chat.presentation.stomp.dto.ChatControlPingPayload;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** 프런트 ping의 requestId가 같은 session의 PONG으로 이어지는지 검증한다. */
class ChatControlMessageControllerTest {

  @Test
  @DisplayName("control ping을 보낸 session에 같은 requestId의 PONG을 보낸다")
  void returnsPongToOriginSession() {
    long userId = 42L;
    String sessionId = "session-1";
    UUID requestId = UUID.randomUUID();
    ChatSessionMessageSender sender = mock(ChatSessionMessageSender.class);
    ChatControlMessageController controller = new ChatControlMessageController(sender);
    StompHeaderAccessor headers = authenticatedHeaders(userId, sessionId);

    controller.pong(
        new ChatControlPingPayload(ChatControlPingPayload.CURRENT_VERSION, requestId), headers);

    verify(sender)
        .sendToSession(
            eq(sessionId),
            eq(ChatStompDestinations.CONTROL_USER_DESTINATION),
            eq(ChatControlEventPayload.pong(requestId)));
  }

  private static StompHeaderAccessor authenticatedHeaders(long userId, String sessionId) {
    StompHeaderAccessor headers = StompHeaderAccessor.create(StompCommand.SEND);
    headers.setSessionId(sessionId);
    headers.setUser(
        new UsernamePasswordAuthenticationToken(
            new ChatStompPrincipal(userId),
            null,
            List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    var attributes = new HashMap<String, Object>();
    attributes.put(StompJwtAuthenticationInterceptor.SESSION_USER_ID, userId);
    headers.setSessionAttributes(attributes);
    return headers;
  }
}
