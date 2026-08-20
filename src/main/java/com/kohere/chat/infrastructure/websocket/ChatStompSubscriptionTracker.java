package com.kohere.chat.infrastructure.websocket;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * 한 WebSocket session 안에서 STOMP subscription ID가 중복되지 않게 기록한다.
 *
 * <p>STOMP의 SUBSCRIBE는 destination뿐 아니라 고유한 {@code id} header가 필요하다. 같은 session이 같은 ID를 다른 경로에 다시
 * 쓰면 Simple Broker가 첫 구독을 유지한 채 두 번째 요청을 명확히 실패시키지 않을 수 있으므로, broker에 도달하기 전에 중복을 거부한다.
 */
@Component
public class ChatStompSubscriptionTracker {

  /** 앱이 사용하는 개인 queue 5개와 현재 채팅방 topic 외에 약간의 여유를 둔 session별 안전 상한이다. */
  static final int MAX_SUBSCRIPTIONS_PER_SESSION = 16;

  /** 비정상적으로 긴 header가 session 메모리를 계속 차지하지 않도록 subscription ID 길이를 제한한다. */
  static final int MAX_SUBSCRIPTION_ID_LENGTH = 128;

  /**
   * session별로 {@code subscriptionId -> destination}을 보관한다.
   *
   * <p>값은 수정할 수 없는 snapshot으로 교체한다. 따라서 SUBSCRIBE와 UNSUBSCRIBE가 다른 스레드에서 동시에 와도 중간 상태가 노출되지 않는다.
   */
  private final ConcurrentHashMap<String, Map<String, String>> subscriptionsBySession =
      new ConcurrentHashMap<>();

  /** 새 구독을 기록하며 중복 ID·중복 경로·과도한 구독을 broker에 도달하기 전에 거부한다. */
  public void register(String sessionId, String subscriptionId, String destination) {
    requireNonBlank(sessionId, "STOMP session id is required");
    requireNonBlank(subscriptionId, "STOMP subscription id is required");
    requireNonBlank(destination, "STOMP subscription destination is required");
    if (subscriptionId.length() > MAX_SUBSCRIPTION_ID_LENGTH) {
      throw new AccessDeniedException("STOMP subscription id is too long");
    }

    subscriptionsBySession.compute(
        sessionId,
        (ignored, current) -> {
          Map<String, String> next = current == null ? new HashMap<>() : new HashMap<>(current);
          if (next.containsKey(subscriptionId)) {
            throw new AccessDeniedException("STOMP subscription id is already in use");
          }
          if (next.containsValue(destination)) {
            throw new AccessDeniedException("STOMP destination is already subscribed");
          }
          if (next.size() >= MAX_SUBSCRIPTIONS_PER_SESSION) {
            throw new AccessDeniedException("Too many STOMP subscriptions in one session");
          }
          next.put(subscriptionId, destination);
          return Map.copyOf(next);
        });
  }

  /** 정상 UNSUBSCRIBE 뒤 같은 ID를 다시 사용할 수 있도록 기록을 제거한다. */
  public void unregister(String sessionId, String subscriptionId) {
    subscriptionsBySession.computeIfPresent(
        sessionId,
        (ignored, current) -> {
          Map<String, String> next = new HashMap<>(current);
          next.remove(subscriptionId);
          return next.isEmpty() ? null : Map.copyOf(next);
        });
  }

  /** 연결이 끝나면 해당 session의 모든 구독 ID를 한 번에 정리한다. 반복 호출해도 안전하다. */
  public void clearSession(String sessionId) {
    subscriptionsBySession.remove(sessionId);
  }

  /** READY 후처리 시 이 구독이 아직 활성 상태인지 확인한다. 빠른 UNSUBSCRIBE와 broker 실패를 구분하는 데 사용한다. */
  public boolean isRegistered(String sessionId, String subscriptionId, String destination) {
    Map<String, String> subscriptions = subscriptionsBySession.get(sessionId);
    return subscriptions != null && destination.equals(subscriptions.get(subscriptionId));
  }

  private static void requireNonBlank(String value, String message) {
    if (value == null || value.isBlank()) {
      throw new AccessDeniedException(message);
    }
  }
}
