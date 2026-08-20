package com.kohere.chat.infrastructure.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.kohere.chat.presentation.stomp.ChatStompDestinations;
import com.kohere.chat.presentation.stomp.dto.ChatControlEventPayload;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/** 개인 응답이 사용자 전체가 아니라 원래 WebSocket session 하나를 향하는지 검증한다. */
class ChatSessionMessageSenderTest {

  @Test
  @DisplayName("user routing key와 simpSessionId에 같은 sessionId를 사용한다")
  void targetsOnlyOriginSession() {
    @SuppressWarnings("unchecked")
    ObjectProvider<SimpMessagingTemplate> provider = mock(ObjectProvider.class);
    SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
    given(provider.getObject()).willReturn(messagingTemplate);

    ChatSessionMessageSender sender = new ChatSessionMessageSender(provider);
    ChatControlEventPayload payload = ChatControlEventPayload.subscriptionReady(10L, 30L);

    sender.sendToSession("session-1", ChatStompDestinations.CONTROL_USER_DESTINATION, payload);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> headersCaptor = ArgumentCaptor.forClass(Map.class);
    verify(messagingTemplate)
        .convertAndSendToUser(
            org.mockito.ArgumentMatchers.eq("session-1"),
            org.mockito.ArgumentMatchers.eq(ChatStompDestinations.CONTROL_USER_DESTINATION),
            org.mockito.ArgumentMatchers.eq(payload),
            headersCaptor.capture());
    assertThat(SimpMessageHeaderAccessor.getSessionId(headersCaptor.getValue()))
        .isEqualTo("session-1");
  }
}
