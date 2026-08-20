package com.kohere.chat.infrastructure.websocket;

import com.kohere.chat.presentation.stomp.ChatStompDestinations;
import com.kohere.chat.presentation.stomp.dto.ChatControlEventPayload;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * 채팅방 SUBSCRIBE가 Simple Broker에 실제 등록된 뒤 {@code SUBSCRIPTION_READY}를 보낸다.
 *
 * <p>프런트가 SUBSCRIBE frame을 보냈다는 사실만으로는 서버 등록 완료를 알 수 없다. 이 interceptor의 {@code
 * afterMessageHandled}는 Simple Broker가 해당 frame 처리를 끝낸 뒤 호출되므로, 그때 DB의 마지막 메시지 번호를 읽어 주면 앱이 놓친 범위를
 * REST로 안전하게 보충할 수 있다.
 */
@Component
public class ChatSubscriptionReadyInterceptor implements ExecutorChannelInterceptor {

  private final ChatStompDestinationResolver destinationResolver;
  private final ChatStompAccessService accessService;
  private final ChatSessionMessageSender sessionMessageSender;
  private final ChatWebSocketSessionManager sessionManager;
  private final ChatStompSubscriptionTracker subscriptionTracker;

  public ChatSubscriptionReadyInterceptor(
      ChatStompDestinationResolver destinationResolver,
      ChatStompAccessService accessService,
      ChatSessionMessageSender sessionMessageSender,
      ChatWebSocketSessionManager sessionManager,
      ChatStompSubscriptionTracker subscriptionTracker) {
    this.destinationResolver = destinationResolver;
    this.accessService = accessService;
    this.sessionMessageSender = sessionMessageSender;
    this.sessionManager = sessionManager;
    this.subscriptionTracker = subscriptionTracker;
  }

  /**
   * 성공적으로 broker가 처리한 room topic SUBSCRIBE에만 준비 완료 이벤트를 만든다.
   *
   * <p>한 inbound frame은 여러 Spring handler를 지나갈 수 있다. handler 종류를 확인하지 않으면 같은 준비 이벤트를 여러 번 보낼 수 있으므로
   * {@link SimpleBrokerMessageHandler} 처리 직후만 선택한다.
   */
  @Override
  public void afterMessageHandled(
      Message<?> message, MessageChannel channel, MessageHandler handler, Exception exception) {
    if (!(handler instanceof SimpleBrokerMessageHandler simpleBroker)) {
      return;
    }

    StompHeaderAccessor headers =
        StompHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
    if (headers == null || headers.getCommand() != StompCommand.SUBSCRIBE) {
      return;
    }

    var roomId = destinationResolver.resolveRoomTopic(headers.getDestination());
    if (roomId.isEmpty()) {
      // 개인 queue 구독은 PING/PONG으로 확인하므로 방 준비 이벤트를 만들지 않는다.
      return;
    }

    String sessionId = ChatStompSessionUser.requireSessionId(headers);
    String subscriptionId = headers.getSubscriptionId();
    if (exception != null || subscriptionId == null) {
      // 구독 완료 여부를 증명할 수 없으므로 반쪽 연결을 남기지 않고 앱의 정상 재연결 절차로 복구시킨다.
      sessionManager.closeForSubscriptionReadyFailure(sessionId);
      return;
    }

    String destination = headers.getDestination();
    if (!isActuallyRegistered(simpleBroker, destination, sessionId, subscriptionId)) {
      if (subscriptionTracker.isRegistered(sessionId, subscriptionId, destination)) {
        // tracker에는 남았는데 broker에는 없다면 실제 등록 실패이므로 반쪽 연결을 정리한다.
        sessionManager.closeForSubscriptionReadyFailure(sessionId);
      }
      // tracker에서도 사라졌다면 앱이 SUBSCRIBE 직후 정상 UNSUBSCRIBE한 것이므로 socket은 유지한다.
      return;
    }

    if (!sessionManager.isSessionOpen(sessionId)
        || !subscriptionTracker.isRegistered(sessionId, subscriptionId, destination)) {
      /*
       * socket 종료의 synthetic DISCONNECT보다 이 SUBSCRIBE handler가 늦게 끝나면 broker에 고아 구독이
       * 다시 생길 수 있다. 실제 session 또는 tracker 정본이 사라졌다면 broker 기록도 여기서 명시적으로 지운다.
       */
      simpleBroker.getSubscriptionRegistry().unregisterAllSubscriptions(sessionId);
      return;
    }

    try {
      long userId = ChatStompSessionUser.requireUserId(headers);

      // 구독 등록 뒤 다시 읽어야 등록 직전까지 DB에 저장된 메시지를 high-watermark에 포함할 수 있다.
      Long highWatermark =
          accessService.authorizeAndGetVisibleHighWatermark(userId, roomId.getAsLong());

      sessionMessageSender.sendToSession(
          sessionId,
          ChatStompDestinations.CONTROL_USER_DESTINATION,
          ChatControlEventPayload.subscriptionReady(roomId.getAsLong(), highWatermark));
    } catch (RuntimeException failure) {
      // Spring channel은 afterMessageHandled 예외를 로그만 남길 수 있다. 직접 socket을 닫아 zombie 구독을 남기지 않는다.
      sessionManager.closeForSubscriptionReadyFailure(sessionId);
    }
  }

  /** Simple Broker registry에 동일 sessionId·subscriptionId가 실제 존재하는지 delivery probe로 확인한다. */
  private static boolean isActuallyRegistered(
      SimpleBrokerMessageHandler simpleBroker,
      String destination,
      String sessionId,
      String subscriptionId) {
    if (destination == null) {
      return false;
    }

    SimpMessageHeaderAccessor probeHeaders =
        SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
    probeHeaders.setDestination(destination);
    probeHeaders.setLeaveMutable(true);

    Message<byte[]> probe =
        MessageBuilder.createMessage(new byte[0], probeHeaders.getMessageHeaders());
    var matches = simpleBroker.getSubscriptionRegistry().findSubscriptions(probe);
    var registeredIds = matches.get(sessionId);
    return registeredIds != null && registeredIds.contains(subscriptionId);
  }
}
