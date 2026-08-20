package com.kohere.chat.infrastructure.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import com.kohere.chat.presentation.stomp.ChatStompDestinations;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** destination을 앞부분이 아닌 전체 문자열로 판별하는지 검증한다. */
class ChatStompDestinationResolverTest {

  private final ChatStompDestinationResolver resolver = new ChatStompDestinationResolver();

  @Test
  @DisplayName("정확한 room topic에서만 양의 Long roomId를 꺼낸다")
  void resolvesOnlyExactRoomTopic() {
    assertThat(resolver.resolveRoomTopic("/topic/chat-rooms/42").getAsLong()).isEqualTo(42L);

    List.of(
            "/topic/chat-rooms/0",
            "/topic/chat-rooms/01",
            "/topic/chat-rooms/-1",
            "/topic/chat-rooms/*",
            "/topic/chat-rooms/42/messages",
            "/topic/chat-rooms/999999999999999999999999")
        .forEach(destination -> assertThat(resolver.resolveRoomTopic(destination)).isEmpty());
  }

  @Test
  @DisplayName("정확한 TEXT SEND 경로에서만 양의 Long roomId를 꺼낸다")
  void resolvesOnlyExactMessageSendDestination() {
    assertThat(resolver.resolveMessageSend("/app/chat-rooms/42/messages").getAsLong())
        .isEqualTo(42L);

    List.of(
            "/app/chat-rooms/0/messages",
            "/app/chat-rooms/01/messages",
            "/app/chat-rooms/-1/messages",
            "/app/chat-rooms/*/messages",
            "/app/chat-rooms/42/messages/extra",
            "/topic/chat-rooms/42")
        .forEach(destination -> assertThat(resolver.resolveMessageSend(destination)).isEmpty());
  }

  @Test
  @DisplayName("공개한 개인 queue와 control SEND만 정확히 식별한다")
  void identifiesExactControlAndUserDestinations() {
    assertThat(resolver.isAllowedUserSubscription(ChatStompDestinations.CONTROL_QUEUE)).isTrue();
    assertThat(resolver.isAllowedUserSubscription("/user/queue/chat-control/extra")).isFalse();
    assertThat(resolver.isControlSend(ChatStompDestinations.CONTROL_SEND)).isTrue();
    assertThat(resolver.isControlSend("/app/chat/control/ping/extra")).isFalse();
  }
}
