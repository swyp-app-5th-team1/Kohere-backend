package com.kohere.chat.infrastructure.websocket;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.kohere.chat.presentation.stomp.ChatStompDestinations;
import com.kohere.chat.presentation.stomp.dto.ChatControlEventPayload;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.broker.SubscriptionRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.util.LinkedMultiValueMap;

/** broker 실제 등록 뒤에만 사용자별 high-watermark 준비 이벤트를 만드는지 검증한다. */
class ChatSubscriptionReadyInterceptorTest {

  private static final long USER_ID = 42L;
  private static final long ROOM_ID = 556L;
  private static final long HIGH_WATERMARK = 700L;
  private static final String SESSION_ID = "session-1";
  private static final String SUBSCRIPTION_ID = "subscription-1";

  private final ChatStompAccessService accessService = mock(ChatStompAccessService.class);
  private final ChatSessionMessageSender sender = mock(ChatSessionMessageSender.class);
  private final ChatWebSocketSessionManager sessionManager =
      mock(ChatWebSocketSessionManager.class);
  private final ChatStompSubscriptionTracker subscriptionTracker =
      mock(ChatStompSubscriptionTracker.class);
  private final SimpleBrokerMessageHandler simpleBroker = mock(SimpleBrokerMessageHandler.class);
  private final SubscriptionRegistry subscriptionRegistry = mock(SubscriptionRegistry.class);
  private final MessageChannel channel = mock(MessageChannel.class);

  private ChatSubscriptionReadyInterceptor interceptor;

  @BeforeEach
  void setUp() {
    interceptor =
        new ChatSubscriptionReadyInterceptor(
            new ChatStompDestinationResolver(),
            accessService,
            sender,
            sessionManager,
            subscriptionTracker);
    given(simpleBroker.getSubscriptionRegistry()).willReturn(subscriptionRegistry);
    given(sessionManager.isSessionOpen(SESSION_ID)).willReturn(true);
    given(
            subscriptionTracker.isRegistered(
                SESSION_ID, SUBSCRIPTION_ID, ChatStompDestinations.roomTopic(ROOM_ID)))
        .willReturn(true);
  }

  @Test
  @DisplayName("room topic이 broker에 실제 등록된 뒤 SUBSCRIPTION_READY를 보낸다")
  void sendsReadyAfterActualBrokerRegistration() {
    Message<byte[]> subscription = roomSubscription();
    var registered = new LinkedMultiValueMap<String, String>();
    registered.add(SESSION_ID, SUBSCRIPTION_ID);
    given(subscriptionRegistry.findSubscriptions(org.mockito.ArgumentMatchers.any()))
        .willReturn(registered);
    given(accessService.authorizeAndGetVisibleHighWatermark(USER_ID, ROOM_ID))
        .willReturn(HIGH_WATERMARK);

    interceptor.afterMessageHandled(subscription, channel, simpleBroker, null);

    verify(sender)
        .sendToSession(
            eq(SESSION_ID),
            eq(ChatStompDestinations.CONTROL_USER_DESTINATION),
            eq(ChatControlEventPayload.subscriptionReady(ROOM_ID, HIGH_WATERMARK)));
    verify(sessionManager).isSessionOpen(SESSION_ID);
    verify(sessionManager, never()).closeForSubscriptionReadyFailure(SESSION_ID);
  }

  @Test
  @DisplayName("broker registry에 없는 구독은 연결을 닫고 거짓 SUBSCRIPTION_READY를 보내지 않는다")
  void closesSessionWhenBrokerDidNotRegisterSubscription() {
    given(subscriptionRegistry.findSubscriptions(org.mockito.ArgumentMatchers.any()))
        .willReturn(new LinkedMultiValueMap<>());
    given(
            subscriptionTracker.isRegistered(
                SESSION_ID, SUBSCRIPTION_ID, ChatStompDestinations.roomTopic(ROOM_ID)))
        .willReturn(true);

    interceptor.afterMessageHandled(roomSubscription(), channel, simpleBroker, null);

    verify(sender, never())
        .sendToSession(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any());
    verify(sessionManager).closeForSubscriptionReadyFailure(SESSION_ID);
  }

  @Test
  @DisplayName("SUBSCRIBE 직후 정상 UNSUBSCRIBE한 경우에는 READY 없이 socket을 유지한다")
  void keepsSessionWhenSubscriptionWasAlreadyUnsubscribed() {
    given(subscriptionRegistry.findSubscriptions(org.mockito.ArgumentMatchers.any()))
        .willReturn(new LinkedMultiValueMap<>());
    given(
            subscriptionTracker.isRegistered(
                SESSION_ID, SUBSCRIPTION_ID, ChatStompDestinations.roomTopic(ROOM_ID)))
        .willReturn(false);

    interceptor.afterMessageHandled(roomSubscription(), channel, simpleBroker, null);

    verify(sender, never())
        .sendToSession(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any());
    verifyNoInteractions(sessionManager);
  }

  @Test
  @DisplayName("socket 종료 뒤 늦게 등록된 broker 구독은 명시적으로 제거한다")
  void removesLateBrokerSubscriptionAfterSocketClosed() {
    var registered = new LinkedMultiValueMap<String, String>();
    registered.add(SESSION_ID, SUBSCRIPTION_ID);
    given(subscriptionRegistry.findSubscriptions(org.mockito.ArgumentMatchers.any()))
        .willReturn(registered);
    given(sessionManager.isSessionOpen(SESSION_ID)).willReturn(false);

    interceptor.afterMessageHandled(roomSubscription(), channel, simpleBroker, null);

    verify(subscriptionRegistry).unregisterAllSubscriptions(SESSION_ID);
    verify(sender, never())
        .sendToSession(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any());
  }

  @Test
  @DisplayName("등록 후 high-watermark 조회가 실패하면 반쪽 구독을 남기지 않고 연결을 닫는다")
  void closesSessionWhenReadyCreationFails() {
    var registered = new LinkedMultiValueMap<String, String>();
    registered.add(SESSION_ID, SUBSCRIPTION_ID);
    given(subscriptionRegistry.findSubscriptions(org.mockito.ArgumentMatchers.any()))
        .willReturn(registered);
    given(accessService.authorizeAndGetVisibleHighWatermark(USER_ID, ROOM_ID))
        .willThrow(new IllegalStateException("temporary database failure"));

    interceptor.afterMessageHandled(roomSubscription(), channel, simpleBroker, null);

    verify(sender, never())
        .sendToSession(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any());
    verify(sessionManager).closeForSubscriptionReadyFailure(SESSION_ID);
  }

  private static Message<byte[]> roomSubscription() {
    StompHeaderAccessor headers = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
    headers.setDestination(ChatStompDestinations.roomTopic(ROOM_ID));
    headers.setSessionId(SESSION_ID);
    headers.setSubscriptionId(SUBSCRIPTION_ID);
    headers.setUser(
        new UsernamePasswordAuthenticationToken(
            new ChatStompPrincipal(USER_ID),
            null,
            List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    var attributes = new HashMap<String, Object>();
    attributes.put(StompJwtAuthenticationInterceptor.SESSION_USER_ID, USER_ID);
    headers.setSessionAttributes(attributes);
    headers.setLeaveMutable(true);
    return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
  }
}
