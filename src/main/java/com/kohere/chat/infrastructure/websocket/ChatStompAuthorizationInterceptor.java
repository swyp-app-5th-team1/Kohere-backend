package com.kohere.chat.infrastructure.websocket;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * 인증된 사용자의 STOMP command와 destination을 허용 목록 방식으로 검사한다.
 *
 * <p>기본값은 거부이며, 공개한 개인 queue·채팅방 topic·control ping·정확한 TEXT 전송 경로만 연다. 특히 클라이언트가 broker용 {@code
 * /topic}, {@code /queue}, {@code /user}로 직접 SEND하거나 임의 queue를 구독하는 것은 허용하지 않는다.
 */
@Component
public class ChatStompAuthorizationInterceptor implements ChannelInterceptor {

  private final ChatStompDestinationResolver destinationResolver;
  private final ChatStompAccessService accessService;
  private final ChatStompSubscriptionTracker subscriptionTracker;
  private final ChatWebSocketSessionManager sessionManager;

  public ChatStompAuthorizationInterceptor(
      ChatStompDestinationResolver destinationResolver,
      ChatStompAccessService accessService,
      ChatStompSubscriptionTracker subscriptionTracker,
      ChatWebSocketSessionManager sessionManager) {
    this.destinationResolver = destinationResolver;
    this.accessService = accessService;
    this.subscriptionTracker = subscriptionTracker;
    this.sessionManager = sessionManager;
  }

  /** command별로 꼭 필요한 조건만 통과시키고 등록되지 않은 기능 frame은 마지막에서 거부한다. */
  @Override
  public Message<?> preSend(Message<?> message, MessageChannel channel) {
    StompHeaderAccessor accessor =
        StompHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
    if (accessor == null) {
      throw new AccessDeniedException("STOMP frame headers are required");
    }

    StompCommand command = accessor.getCommand();
    if (command == null) {
      // heartbeat는 body나 destination이 없는 transport 생존 신호다.
      return message;
    }

    // CONNECT/STOMP는 앞 순서의 JWT interceptor가 인증을 끝냈다.
    if (command == StompCommand.CONNECT || command == StompCommand.STOMP) {
      return message;
    }

    // 자동 DISCONNECT는 인증 정보가 정리된 뒤 올 수 있다. 남은 구독 ID만 session 단위로 멱등 정리한다.
    if (command == StompCommand.DISCONNECT) {
      String sessionId = accessor.getSessionId();
      if (sessionId != null) {
        subscriptionTracker.clearSession(sessionId);
      }
      return message;
    }

    long userId = ChatStompSessionUser.requireUserId(accessor);
    if (command == StompCommand.SUBSCRIBE) {
      authorizeSubscription(accessor, userId);
      return message;
    }

    if (command == StompCommand.SEND) {
      authorizeSend(accessor.getDestination(), userId);
      return message;
    }

    if (command == StompCommand.UNSUBSCRIBE) {
      String subscriptionId = requireSubscriptionId(accessor);
      subscriptionTracker.unregister(
          ChatStompSessionUser.requireSessionId(accessor), subscriptionId);
      return message;
    }

    // ACK/NACK/BEGIN/COMMIT/ABORT처럼 이번 채팅 계약에서 사용하지 않는 command는 명시적으로 닫는다.
    throw forbidden();
  }

  /** 개인 queue는 정확한 다섯 경로만, room topic은 참여 중이고 현재 보이는 방만 허용한다. */
  private void authorizeSubscription(StompHeaderAccessor accessor, long userId) {
    String destination = accessor.getDestination();
    String sessionId = ChatStompSessionUser.requireSessionId(accessor);
    String subscriptionId = requireSubscriptionId(accessor);

    // socket 종료와 늦은 SUBSCRIBE가 엇갈리면 broker에 고아 구독이 남을 수 있으므로 등록 직전 실제 연결을 확인한다.
    if (!sessionManager.isSessionOpen(sessionId)) {
      throw forbidden();
    }

    if (destinationResolver.isAllowedUserSubscription(destination)) {
      registerWhileSessionOpen(sessionId, subscriptionId, destination);
      return;
    }

    var roomId = destinationResolver.resolveRoomTopic(destination);
    if (roomId.isPresent()) {
      // 반환되는 high-watermark는 실제 broker 등록 뒤 다시 계산한다. 여기서는 구독 진입 권한만 확인한다.
      accessService.authorizeAndGetVisibleHighWatermark(userId, roomId.getAsLong());
      registerWhileSessionOpen(sessionId, subscriptionId, destination);
      return;
    }
    throw forbidden();
  }

  /** control ping과 참여 중이며 현재 보이는 채팅방의 TEXT 전송 경로만 허용한다. */
  private void authorizeSend(String destination, long userId) {
    if (destinationResolver.isControlSend(destination)) {
      return;
    }

    var roomId = destinationResolver.resolveMessageSend(destination);
    if (roomId.isPresent()) {
      /*
       * interceptor 검사는 잘못된 frame을 handler 전에 빠르게 거르는 1차 방어다. 이 검사 뒤 차단이나 삭제가 발생할 수 있으므로
       * 실제 저장 서비스도 같은 참여자·가시성·차단 조건을 트랜잭션 안에서 다시 확인한다.
       */
      accessService.authorizeAndGetVisibleHighWatermark(userId, roomId.getAsLong());
      return;
    }
    throw forbidden();
  }

  private static AccessDeniedException forbidden() {
    return new AccessDeniedException("STOMP destination is not allowed");
  }

  /** 상태 확인과 tracker 등록 사이에 socket이 닫힌 경합도 재확인해 기록을 즉시 되돌린다. */
  private void registerWhileSessionOpen(
      String sessionId, String subscriptionId, String destination) {
    subscriptionTracker.register(sessionId, subscriptionId, destination);
    if (!sessionManager.isSessionOpen(sessionId)) {
      subscriptionTracker.unregister(sessionId, subscriptionId);
      throw forbidden();
    }
  }

  /** Simple Broker가 조용히 무시하지 않도록 SUBSCRIBE·UNSUBSCRIBE의 고유 ID를 먼저 필수 검증한다. */
  private static String requireSubscriptionId(StompHeaderAccessor accessor) {
    String subscriptionId = accessor.getSubscriptionId();
    if (subscriptionId == null || subscriptionId.isBlank()) {
      throw forbidden();
    }
    return subscriptionId;
  }
}
