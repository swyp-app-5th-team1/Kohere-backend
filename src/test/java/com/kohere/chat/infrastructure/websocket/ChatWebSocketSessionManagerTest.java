package com.kohere.chat.infrastructure.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/** handshake-only timeout과 JWT 만료 close가 실제 socket에 연결되는지 검증한다. */
class ChatWebSocketSessionManagerTest {

  private final TaskScheduler scheduler = mock(TaskScheduler.class);
  private final ChatWebSocketProperties properties = new ChatWebSocketProperties();
  private final ChatStompSubscriptionTracker subscriptionTracker =
      mock(ChatStompSubscriptionTracker.class);
  private final List<Runnable> scheduledTasks = new ArrayList<>();

  private ChatWebSocketSessionManager manager;

  @BeforeEach
  void setUp() {
    properties.setTimeToFirstMessage(Duration.ofSeconds(10));
    given(scheduler.schedule(any(Runnable.class), any(Instant.class)))
        .willAnswer(
            invocation -> {
              scheduledTasks.add(invocation.getArgument(0));
              return mock(ScheduledFuture.class);
            });
    manager = new ChatWebSocketSessionManager(scheduler, properties, subscriptionTracker);
  }

  /** handshake 뒤 CONNECT를 보내지 않으면 예약 작업이 1008 상태로 socket을 닫는다. */
  @Test
  @DisplayName("CONNECT timeout: 인증하지 않은 WebSocket을 닫는다")
  void closesSessionThatNeverConnects() throws Exception {
    WebSocketSession session = openSession("session-1");
    manager.register(session);

    assertThat(scheduledTasks).hasSize(1);
    scheduledTasks.getFirst().run();

    ArgumentCaptor<CloseStatus> closeStatus = ArgumentCaptor.forClass(CloseStatus.class);
    verify(session).close(closeStatus.capture());
    assertThat(closeStatus.getValue().getCode()).isEqualTo(1008);
    assertThat(closeStatus.getValue().getReason()).isEqualTo("STOMP_CONNECT_TIMEOUT");
  }

  /** 인증 성공 뒤에는 CONNECT timeout 대신 JWT expiresAt 작업이 실제 연결을 닫는다. */
  @Test
  @DisplayName("JWT expiry: 인증된 WebSocket을 token 만료 시각에 닫는다")
  void replacesConnectTimeoutWithJwtExpiration() throws Exception {
    WebSocketSession session = openSession("session-2");
    manager.register(session);
    manager.scheduleExpiration("session-2", Instant.now().plusSeconds(3600));

    assertThat(scheduledTasks).hasSize(2);

    // cancel(false) 직전에 timeout Runnable이 실행되기 시작한 경합을 재현해도 인증 session은 닫히지 않아야 한다.
    scheduledTasks.getFirst().run();
    verify(session, never()).close(any(CloseStatus.class));

    scheduledTasks.get(1).run();

    ArgumentCaptor<CloseStatus> closeStatus = ArgumentCaptor.forClass(CloseStatus.class);
    verify(session).close(closeStatus.capture());
    assertThat(closeStatus.getValue().getCode()).isEqualTo(1008);
    assertThat(closeStatus.getValue().getReason()).isEqualTo("TOKEN_EXPIRED");
  }

  /** READY를 만들 수 없는 반쪽 구독도 같은 실제 socket을 정책 위반 상태로 닫는다. */
  @Test
  @DisplayName("구독 준비 실패: 재연결할 수 있도록 WebSocket을 닫는다")
  void closesSessionWhenSubscriptionReadyFails() throws Exception {
    WebSocketSession session = openSession("session-3");
    manager.register(session);

    manager.closeForSubscriptionReadyFailure("session-3");

    ArgumentCaptor<CloseStatus> closeStatus = ArgumentCaptor.forClass(CloseStatus.class);
    verify(session).close(closeStatus.capture());
    assertThat(closeStatus.getValue().getCode()).isEqualTo(1008);
    assertThat(closeStatus.getValue().getReason()).isEqualTo("SUBSCRIPTION_READY_FAILED");
    verify(subscriptionTracker).clearSession("session-3");
  }

  private static WebSocketSession openSession(String sessionId) {
    WebSocketSession session = mock(WebSocketSession.class);
    given(session.getId()).willReturn(sessionId);
    given(session.isOpen()).willReturn(true);
    return session;
  }
}
