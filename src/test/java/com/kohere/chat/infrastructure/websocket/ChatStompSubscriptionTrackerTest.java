package com.kohere.chat.infrastructure.websocket;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

/** 한 session의 구독 정보가 중복되거나 무한히 늘어나지 않는지 검증한다. */
class ChatStompSubscriptionTrackerTest {

  private static final String SESSION_ID = "session-1";

  @Test
  @DisplayName("같은 ID뿐 아니라 같은 destination의 중복 구독도 거부한다")
  void rejectsDuplicateIdAndDestination() {
    ChatStompSubscriptionTracker tracker = new ChatStompSubscriptionTracker();
    tracker.register(SESSION_ID, "subscription-1", "/user/queue/chat-control");

    assertThatThrownBy(
            () -> tracker.register(SESSION_ID, "subscription-1", "/user/queue/chat-errors"))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () -> tracker.register(SESSION_ID, "subscription-2", "/user/queue/chat-control"))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  @DisplayName("한 session의 구독 개수와 subscription ID 길이에 안전 상한을 둔다")
  void limitsSubscriptionCountAndIdLength() {
    ChatStompSubscriptionTracker tracker = new ChatStompSubscriptionTracker();
    for (int index = 0;
        index < ChatStompSubscriptionTracker.MAX_SUBSCRIPTIONS_PER_SESSION;
        index++) {
      tracker.register(SESSION_ID, "subscription-" + index, "/topic/example/" + index);
    }

    assertThatThrownBy(() -> tracker.register(SESSION_ID, "one-more", "/topic/example/overflow"))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                new ChatStompSubscriptionTracker()
                    .register(
                        SESSION_ID,
                        "a".repeat(ChatStompSubscriptionTracker.MAX_SUBSCRIPTION_ID_LENGTH + 1),
                        "/user/queue/chat-control"))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  @DisplayName("UNSUBSCRIBE나 연결 종료 뒤에는 같은 구독을 다시 등록할 수 있다")
  void releasesSubscriptions() {
    ChatStompSubscriptionTracker tracker = new ChatStompSubscriptionTracker();
    tracker.register(SESSION_ID, "subscription-1", "/user/queue/chat-control");
    tracker.unregister(SESSION_ID, "subscription-1");

    assertThatCode(() -> tracker.register(SESSION_ID, "subscription-1", "/user/queue/chat-control"))
        .doesNotThrowAnyException();

    tracker.clearSession(SESSION_ID);
    assertThatCode(() -> tracker.register(SESSION_ID, "subscription-1", "/user/queue/chat-control"))
        .doesNotThrowAnyException();
  }
}
