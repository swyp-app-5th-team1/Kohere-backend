package com.kohere.chat.infrastructure.websocket;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/**
 * 실제 WebSocket 연결과 JWT 만료 작업을 session ID로 연결해 관리한다.
 *
 * <p>CONNECT 순간에만 JWT를 확인하면, 그때는 유효했던 1시간 토큰으로 소켓이 며칠 동안 살아 있을 수 있다. 인증 성공 시 검증된 만료 시각을 예약하고, 시간이
 * 되면 서버가 연결을 닫아 앱이 새 access token으로 재연결하도록 한다.
 */
@Slf4j
@Component
public class ChatWebSocketSessionManager {

  /** 정책 위반 close code를 사용하되 reason에는 토큰이나 사용자 정보를 넣지 않는다. */
  private static final CloseStatus TOKEN_EXPIRED_CLOSE_STATUS =
      CloseStatus.POLICY_VIOLATION.withReason("TOKEN_EXPIRED");

  /** handshake 뒤 STOMP CONNECT를 보내지 않은 연결을 닫을 때 사용하는 안전한 사유다. */
  private static final CloseStatus CONNECT_TIMEOUT_CLOSE_STATUS =
      CloseStatus.POLICY_VIOLATION.withReason("STOMP_CONNECT_TIMEOUT");

  /** 구독 등록 뒤 동기화 기준을 만들지 못한 연결을 닫을 때 사용하는 안전한 사유다. */
  private static final CloseStatus SUBSCRIPTION_READY_FAILED_CLOSE_STATUS =
      CloseStatus.POLICY_VIOLATION.withReason("SUBSCRIPTION_READY_FAILED");

  /** 여러 WebSocket 처리 스레드가 동시에 접근하므로 thread-safe map을 사용한다. */
  private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

  /** 연결이 먼저 끊기면 예약한 만료 작업을 취소하기 위해 session별 작업을 보관한다. */
  private final Map<String, ScheduledFuture<?>> expirationTasks = new ConcurrentHashMap<>();

  /** handshake만 하고 인증하지 않은 socket이 자원을 계속 점유하지 않도록 별도 timeout 작업을 둔다. */
  private final Map<String, ScheduledFuture<?>> connectTimeoutTasks = new ConcurrentHashMap<>();

  /** CONNECT timeout 취소와 실행이 동시에 겹쳐도 이미 인증된 session을 닫지 않게 하는 상태 정본이다. */
  private final Set<String> authenticatedSessionIds = ConcurrentHashMap.newKeySet();

  private final TaskScheduler taskScheduler;
  private final ChatWebSocketProperties properties;
  private final ChatStompSubscriptionTracker subscriptionTracker;

  public ChatWebSocketSessionManager(
      @Qualifier("chatWebSocketTaskScheduler") TaskScheduler taskScheduler,
      ChatWebSocketProperties properties,
      ChatStompSubscriptionTracker subscriptionTracker) {
    this.taskScheduler = taskScheduler;
    this.properties = properties;
    this.subscriptionTracker = subscriptionTracker;
  }

  /** HTTP handshake 직후 익명 session을 등록하고, 정해진 시간 안에 CONNECT가 없으면 닫도록 예약한다. */
  public void register(WebSocketSession session) {
    authenticatedSessionIds.remove(session.getId());
    sessions.put(session.getId(), session);
    ScheduledFuture<?> task =
        taskScheduler.schedule(
            () -> closeUnauthenticatedSession(session.getId()),
            Instant.now().plus(properties.getTimeToFirstMessage()));
    if (task != null) {
      connectTimeoutTasks.put(session.getId(), task);
      removeTaskIfSessionAlreadyClosed(session.getId(), task, connectTimeoutTasks);
    }
  }

  /**
   * STOMP CONNECT 인증이 끝난 session을 JWT 만료 시각에 닫도록 예약한다.
   *
   * <p>같은 session에 CONNECT가 잘못 중복되더라도 이전 작업을 취소하고 하나만 남겨 중복 close를 방지한다.
   */
  public void scheduleExpiration(String sessionId, Instant expiresAt) {
    // cancel(false)가 이미 실행을 시작한 timeout 작업을 멈추지 못할 수 있으므로 인증 상태를 먼저 표시한다.
    authenticatedSessionIds.add(sessionId);
    // CONNECT 인증을 완료했으므로 handshake-only timeout은 더 이상 실행되면 안 된다.
    cancelConnectTimeoutTask(sessionId);
    cancelExpirationTask(sessionId);
    ScheduledFuture<?> task =
        taskScheduler.schedule(() -> closeExpiredSession(sessionId), expiresAt);
    if (task != null) {
      expirationTasks.put(sessionId, task);
      removeTaskIfSessionAlreadyClosed(sessionId, task, expirationTasks);
    }
  }

  /** WebSocket 연결이 끝나면 session과 예약 작업을 함께 제거한다. 여러 번 호출되어도 안전하다. */
  public void unregister(String sessionId) {
    sessions.remove(sessionId);
    authenticatedSessionIds.remove(sessionId);
    // synthetic DISCONNECT 순서에 의존하지 않고 실제 socket 종료 지점에서 STOMP 구독 기록도 함께 없앤다.
    subscriptionTracker.clearSession(sessionId);
    cancelConnectTimeoutTask(sessionId);
    cancelExpirationTask(sessionId);
  }

  /** interceptor가 후속 frame 처리 직전에도 만료를 재확인할 수 있게 현재 시각과 비교한다. */
  public boolean isExpired(Instant expiresAt, Instant now) {
    return !now.isBefore(expiresAt);
  }

  /** 종료 중인 socket의 늦은 SUBSCRIBE가 tracker와 broker에 다시 등록되지 않도록 실제 연결 상태를 확인한다. */
  public boolean isSessionOpen(String sessionId) {
    WebSocketSession session = sessions.get(sessionId);
    return session != null && session.isOpen();
  }

  /**
   * broker 구독 뒤 high-watermark 계산이나 개인 응답 전송에 실패한 socket을 닫는다.
   *
   * <p>구독만 남고 앱은 준비 완료를 받지 못하는 반쪽 상태를 유지하는 것보다, 연결을 닫아 앱의 정상 재연결 절차로 복구하는 편이 안전하다.
   */
  public void closeForSubscriptionReadyFailure(String sessionId) {
    closeSession(sessionId, SUBSCRIPTION_READY_FAILED_CLOSE_STATUS);
  }

  /** 예약 시각에 아직 열려 있는 session만 정책 위반 상태로 닫는다. */
  private void closeExpiredSession(String sessionId) {
    closeSession(sessionId, TOKEN_EXPIRED_CLOSE_STATUS);
  }

  /** 제한 시간 안에 STOMP CONNECT로 인증하지 않은 socket을 정리한다. */
  private void closeUnauthenticatedSession(String sessionId) {
    // CONNECT 성공과 timeout 실행이 경합하면 인증 상태가 최종 판정이다. future 취소 성공 여부에 의존하지 않는다.
    if (authenticatedSessionIds.contains(sessionId)) {
      return;
    }
    closeSession(sessionId, CONNECT_TIMEOUT_CLOSE_STATUS);
  }

  /** 만료·CONNECT timeout이 같은 방식으로 실제 socket을 안전하게 닫도록 공통 처리한다. */
  private void closeSession(String sessionId, CloseStatus closeStatus) {
    WebSocketSession session = sessions.get(sessionId);
    if (session == null || !session.isOpen()) {
      unregister(sessionId);
      return;
    }

    try {
      session.close(closeStatus);
    } catch (IOException e) {
      // session ID만 남기고 token·본문·사용자 정보는 로그에 기록하지 않는다.
      log.debug("Failed to close chat WebSocket session: sessionId={}", sessionId, e);
    } finally {
      unregister(sessionId);
    }
  }

  /** session의 이전 만료 작업이 있으면 map에서 꺼낸 뒤 실행되지 않도록 취소한다. */
  private void cancelExpirationTask(String sessionId) {
    ScheduledFuture<?> previous = expirationTasks.remove(sessionId);
    if (previous != null) {
      previous.cancel(false);
    }
  }

  /** CONNECT가 성공하거나 socket이 끝나면 handshake-only timeout을 취소한다. */
  private void cancelConnectTimeoutTask(String sessionId) {
    ScheduledFuture<?> previous = connectTimeoutTasks.remove(sessionId);
    if (previous != null) {
      previous.cancel(false);
    }
  }

  /**
   * scheduler가 future를 반환하기 전에 socket 종료나 즉시 실행이 끝난 경합을 정리한다.
   *
   * <p>{@code unregister}가 map에 아직 들어오지 않은 future를 보지 못할 수 있으므로 put 직후 session 존재를 재확인한다.
   */
  private void removeTaskIfSessionAlreadyClosed(
      String sessionId, ScheduledFuture<?> task, Map<String, ScheduledFuture<?>> taskMap) {
    if (!sessions.containsKey(sessionId) && taskMap.remove(sessionId, task)) {
      task.cancel(false);
      // CONNECT 인증과 socket 종료가 엇갈린 경우 future뿐 아니라 인증 상태 표식도 남기지 않는다.
      authenticatedSessionIds.remove(sessionId);
    }
  }
}
