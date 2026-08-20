package com.kohere.chat.infrastructure.websocket;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.kohere.chat.presentation.stomp.ChatStompDestinations;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** 허용한 개인 queue·room topic만 열리고 아직 미구현인 TEXT SEND는 닫혀 있는지 검증한다. */
class ChatStompAuthorizationInterceptorTest {

  private static final long USER_ID = 42L;
  private static final long ROOM_ID = 556L;
  private static final String SESSION_ID = "session-1";
  private static final String SUBSCRIPTION_ID = "subscription-1";

  private final ChatStompAccessService accessService = mock(ChatStompAccessService.class);
  private final ChatStompSubscriptionTracker subscriptionTracker =
      new ChatStompSubscriptionTracker();
  private final ChatWebSocketSessionManager sessionManager =
      mock(ChatWebSocketSessionManager.class);
  private final MessageChannel channel = mock(MessageChannel.class);

  private ChatStompAuthorizationInterceptor interceptor;

  @BeforeEach
  void setUp() {
    given(sessionManager.isSessionOpen(SESSION_ID)).willReturn(true);
    interceptor =
        new ChatStompAuthorizationInterceptor(
            new ChatStompDestinationResolver(), accessService, subscriptionTracker, sessionManager);
  }

  /** CONNECT 인증은 앞 interceptor가 담당하고 DISCONNECT는 정리 frame이므로 그대로 통과한다. */
  @Test
  @DisplayName("CONNECT·STOMP와 DISCONNECT는 허용한다")
  void allowsConnectionLifecycleFrames() {
    assertThatCode(() -> intercept(frame(StompCommand.CONNECT, null))).doesNotThrowAnyException();
    assertThatCode(() -> intercept(frame(StompCommand.STOMP, null))).doesNotThrowAnyException();
    assertThatCode(() -> intercept(frame(StompCommand.DISCONNECT, null)))
        .doesNotThrowAnyException();
  }

  /** 앱이 연결 직후 준비하는 다섯 개인 queue만 정확히 허용한다. */
  @Test
  @DisplayName("공개한 다섯 개인 queue는 인증 session에 허용한다")
  void allowsExactUserQueueSubscriptions() {
    List.of(
            ChatStompDestinations.ACK_QUEUE,
            ChatStompDestinations.ERROR_QUEUE,
            ChatStompDestinations.ROOM_EVENT_QUEUE,
            ChatStompDestinations.TRANSLATION_QUEUE,
            ChatStompDestinations.CONTROL_QUEUE)
        .forEach(
            destination ->
                assertThatCode(
                        () ->
                            intercept(
                                authenticatedFrame(
                                    StompCommand.SUBSCRIBE,
                                    destination,
                                    destination + "-subscription")))
                    .doesNotThrowAnyException());

    verifyNoInteractions(accessService);
  }

  /** room topic은 경로 속 roomId로 DB 참여자·가시성 검사를 통과해야 한다. */
  @Test
  @DisplayName("현재 참여 중이고 보이는 채팅방 topic만 구독한다")
  void allowsAuthorizedRoomSubscription() {
    given(accessService.authorizeAndGetVisibleHighWatermark(USER_ID, ROOM_ID)).willReturn(700L);

    assertThatCode(
            () ->
                intercept(
                    authenticatedFrame(
                        StompCommand.SUBSCRIBE,
                        ChatStompDestinations.roomTopic(ROOM_ID),
                        SUBSCRIPTION_ID)))
        .doesNotThrowAnyException();

    verify(accessService).authorizeAndGetVisibleHighWatermark(USER_ID, ROOM_ID);
  }

  /** topic·raw queue·다른 사용자처럼 계약에 없는 경로는 앞부분이 비슷해도 모두 막는다. */
  @Test
  @DisplayName("raw queue·와일드카드·임의 user destination 구독을 거부한다")
  void deniesUnregisteredSubscriptions() {
    List.of(
            "/queue/chat-control",
            "/topic/chat-rooms/*",
            "/topic/chat-rooms/01",
            "/user/42/queue/chat-control",
            "/user/queue/unknown")
        .forEach(
            destination ->
                assertThatThrownBy(
                        () ->
                            intercept(
                                authenticatedFrame(
                                    StompCommand.SUBSCRIBE,
                                    destination,
                                    destination + "-subscription")))
                    .isInstanceOf(AccessDeniedException.class));
  }

  /** Simple Broker가 조용히 무시하는 잘못된 SUBSCRIBE를 준비 완료로 오인하지 않게 ID를 필수로 한다. */
  @Test
  @DisplayName("subscription ID 누락과 같은 session의 중복 ID를 거부한다")
  void deniesMissingOrDuplicateSubscriptionId() {
    assertThatThrownBy(
            () ->
                intercept(
                    authenticatedFrame(
                        StompCommand.SUBSCRIBE, ChatStompDestinations.CONTROL_QUEUE, null)))
        .isInstanceOf(AccessDeniedException.class);

    intercept(
        authenticatedFrame(
            StompCommand.SUBSCRIBE, ChatStompDestinations.CONTROL_QUEUE, SUBSCRIPTION_ID));

    assertThatThrownBy(
            () ->
                intercept(
                    authenticatedFrame(
                        StompCommand.SUBSCRIBE, ChatStompDestinations.ACK_QUEUE, SUBSCRIPTION_ID)))
        .isInstanceOf(AccessDeniedException.class);
  }

  /** 앱이 구독을 해제하면 그 ID를 나중에 다시 사용할 수 있다. */
  @Test
  @DisplayName("UNSUBSCRIBE 뒤 같은 subscription ID를 다시 사용할 수 있다")
  void releasesSubscriptionIdOnUnsubscribe() {
    intercept(
        authenticatedFrame(
            StompCommand.SUBSCRIBE, ChatStompDestinations.CONTROL_QUEUE, SUBSCRIPTION_ID));
    intercept(authenticatedFrame(StompCommand.UNSUBSCRIBE, null, SUBSCRIPTION_ID));

    assertThatCode(
            () ->
                intercept(
                    authenticatedFrame(
                        StompCommand.SUBSCRIBE,
                        ChatStompDestinations.CONTROL_QUEUE,
                        SUBSCRIPTION_ID)))
        .doesNotThrowAnyException();
  }

  /** 현재 단계는 제어 ping만 SEND 가능하고 TEXT·broker 직접 발행은 다음 단계까지 닫는다. */
  @Test
  @DisplayName("control ping만 SEND하고 TEXT와 broker 직접 SEND는 거부한다")
  void allowsOnlyControlPingSend() {
    assertThatCode(
            () ->
                intercept(
                    authenticatedFrame(
                        StompCommand.SEND, ChatStompDestinations.CONTROL_SEND, null)))
        .doesNotThrowAnyException();

    List.of(
            "/app/chat-rooms/556/messages",
            "/topic/chat-rooms/556",
            "/queue/chat-control",
            "/user/queue/chat-control")
        .forEach(
            destination ->
                assertThatThrownBy(
                        () -> intercept(authenticatedFrame(StompCommand.SEND, destination, null)))
                    .isInstanceOf(AccessDeniedException.class));
  }

  /** 종료된 socket의 늦은 SUBSCRIBE는 tracker나 broker에 고아 구독을 만들기 전에 막는다. */
  @Test
  @DisplayName("이미 종료된 WebSocket session의 늦은 SUBSCRIBE를 거부한다")
  void deniesSubscriptionForClosedSession() {
    given(sessionManager.isSessionOpen(SESSION_ID)).willReturn(false);

    assertThatThrownBy(
            () ->
                intercept(
                    authenticatedFrame(
                        StompCommand.SUBSCRIBE,
                        ChatStompDestinations.CONTROL_QUEUE,
                        SUBSCRIPTION_ID)))
        .isInstanceOf(AccessDeniedException.class);
  }

  /** 첫 상태 확인 직후 socket이 닫혀도 tracker 기록을 되돌리고 broker로 넘기지 않는다. */
  @Test
  @DisplayName("구독 기록 도중 WebSocket이 닫히면 tracker 등록을 rollback한다")
  void rollsBackSubscriptionWhenSessionClosesDuringRegistration() {
    given(sessionManager.isSessionOpen(SESSION_ID)).willReturn(true, false);

    assertThatThrownBy(
            () ->
                intercept(
                    authenticatedFrame(
                        StompCommand.SUBSCRIBE,
                        ChatStompDestinations.CONTROL_QUEUE,
                        SUBSCRIPTION_ID)))
        .isInstanceOf(AccessDeniedException.class);
    assertThatCode(
            () ->
                subscriptionTracker.register(
                    SESSION_ID, SUBSCRIPTION_ID, ChatStompDestinations.CONTROL_QUEUE))
        .doesNotThrowAnyException();
  }

  private Message<byte[]> frame(StompCommand command, String destination) {
    StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
    accessor.setDestination(destination);
    accessor.setSessionId(SESSION_ID);
    accessor.setLeaveMutable(true);
    return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
  }

  /** 1단계 JWT interceptor가 CONNECT 뒤 frame에 남기는 인증 정보와 같은 모양을 만든다. */
  private Message<byte[]> authenticatedFrame(
      StompCommand command, String destination, String subscriptionId) {
    StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
    accessor.setDestination(destination);
    accessor.setSessionId(SESSION_ID);
    accessor.setSubscriptionId(subscriptionId);

    var principal = new ChatStompPrincipal(USER_ID);
    accessor.setUser(
        new UsernamePasswordAuthenticationToken(
            principal, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    var sessionAttributes = new HashMap<String, Object>();
    sessionAttributes.put(StompJwtAuthenticationInterceptor.SESSION_USER_ID, USER_ID);
    accessor.setSessionAttributes(sessionAttributes);
    accessor.setLeaveMutable(true);

    return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
  }

  private void intercept(Message<?> message) {
    interceptor.preSend(message, channel);
  }
}
