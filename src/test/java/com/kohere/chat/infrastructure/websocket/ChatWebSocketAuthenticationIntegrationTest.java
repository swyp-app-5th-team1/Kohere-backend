package com.kohere.chat.infrastructure.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kohere.TestcontainersConfiguration;
import com.kohere.chat.application.BookingCardService;
import com.kohere.chat.application.BookingCardWriter;
import com.kohere.chat.application.ChatTextMessageService;
import com.kohere.chat.application.TextMessageSaveResult;
import com.kohere.chat.domain.BookingCardPayload;
import com.kohere.chat.domain.ChatClientMessageConflictException;
import com.kohere.chat.domain.ChatRoom;
import com.kohere.chat.domain.ChatRoomMember;
import com.kohere.chat.domain.ChatRoomMemberRepository;
import com.kohere.chat.domain.ChatRoomRepository;
import com.kohere.chat.domain.Message;
import com.kohere.chat.domain.MessageType;
import com.kohere.chat.presentation.stomp.ChatStompDestinations;
import com.kohere.chat.presentation.stomp.dto.ChatControlEventPayload;
import com.kohere.chat.presentation.stomp.dto.ChatControlPingPayload;
import com.kohere.chat.presentation.stomp.dto.ChatMessageAckPayload;
import com.kohere.chat.presentation.stomp.dto.ChatMessageCreatedPayload;
import com.kohere.chat.presentation.stomp.dto.ChatMessageErrorPayload;
import com.kohere.chat.presentation.stomp.dto.ChatMessageSendPayload;
import com.kohere.chat.presentation.stomp.dto.ChatStompEventType;
import com.kohere.common.security.JwtTokenService;
import com.kohere.user.api.UserAccountService;
import com.kohere.user.api.UserAccountView;
import java.lang.reflect.Type;
import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.StompSubProtocolHandler;
import org.springframework.web.socket.messaging.SubProtocolWebSocketHandler;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * 실제 랜덤 포트 WebSocket 서버와 STOMP Java client를 연결해 HTTP handshake부터 CONNECT 인증까지 검증한다.
 *
 * <p>단위 테스트는 interceptor 분기를 빠르게 확인하고, 이 테스트는 설정 파일·endpoint·broker·Principal 전달이 실제 Spring 연결에서도
 * 함께 동작하는지 확인한다.
 */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {
      "app.chat.websocket.allowed-origins[0]=http://allowed.test",
      "app.jwt.access-ttl-seconds=5"
    })
@ActiveProfiles("test")
@Import({
  TestcontainersConfiguration.class,
  ChatWebSocketAuthenticationIntegrationTest.Events.class
})
class ChatWebSocketAuthenticationIntegrationTest {

  private static final long USER_ID = 42L;
  private static final long RECIPIENT_ID = 77L;

  @LocalServerPort private int port;
  @Autowired private JwtTokenService jwtTokenService;
  @Autowired private ConnectedUserRecorder connectedUserRecorder;
  @Autowired private SubProtocolWebSocketHandler subProtocolWebSocketHandler;
  @Autowired private SimpleBrokerMessageHandler simpleBrokerMessageHandler;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private ChatRealtimeMessagePublisher realtimeMessagePublisher;

  @Autowired
  @Qualifier("chatWebSocketTaskScheduler")
  private TaskScheduler chatWebSocketTaskScheduler;

  // 계정 상태 조회의 네트워크 경계만 대체하고 JWT·WebSocket·broker 설정은 실제 빈을 사용한다.
  @MockitoBean private UserAccountService userAccountService;
  @MockitoBean private ChatRoomRepository chatRoomRepository;
  @MockitoBean private ChatRoomMemberRepository memberRepository;
  @MockitoBean private ChatTextMessageService chatTextMessageService;

  private ThreadPoolTaskScheduler clientScheduler;
  private WebSocketStompClient stompClient;
  private final LinkedBlockingQueue<Throwable> clientFrameErrors = new LinkedBlockingQueue<>();

  @BeforeEach
  void setUp() {
    clientScheduler = new ThreadPoolTaskScheduler();
    clientScheduler.setPoolSize(1);
    clientScheduler.setThreadNamePrefix("stomp-test-client-");
    clientScheduler.initialize();

    stompClient = new WebSocketStompClient(new StandardWebSocketClient());
    stompClient.setTaskScheduler(clientScheduler);
    // record payload를 실제 JSON STOMP body로 보내고 받을 수 있게 운영과 같은 Jackson 변환기를 사용한다.
    MappingJackson2MessageConverter messageConverter = new MappingJackson2MessageConverter();
    // Spring Boot가 Java 시간 모듈까지 등록한 ObjectMapper를 써야 sentAt 같은 Instant를 읽을 수 있다.
    messageConverter.setObjectMapper(objectMapper);
    stompClient.setMessageConverter(messageConverter);
    connectedUserRecorder.clear();
    clientFrameErrors.clear();
  }

  @AfterEach
  void tearDown() {
    stompClient.stop();
    clientScheduler.shutdown();
  }

  /** 허용 Origin과 정상 ACTIVE access token이면 CONNECTED 뒤 서버 Principal 이름이 userId로 고정된다. */
  @Test
  @DisplayName("실제 STOMP 연결: ACTIVE access token으로 CONNECT 성공")
  void connectsWithActiveAccessToken() throws Exception {
    given(userAccountService.getAccount(USER_ID))
        .willReturn(new UserAccountView(USER_ID, "ACTIVE", "User", "user@example.com"));

    StompSession session =
        connect("http://allowed.test", "Bearer " + jwtTokenService.issueAccessToken(USER_ID));

    assertThat(session.isConnected()).isTrue();
    Principal connectedUser = connectedUserRecorder.await();
    assertThat(connectedUser).isNotNull();
    assertThat(connectedUser.getName()).isEqualTo(String.valueOf(USER_ID));
    session.disconnect();
  }

  /** 설정에 없는 브라우저 Origin은 STOMP JWT를 보기 전 HTTP handshake에서 거부한다. */
  @Test
  @DisplayName("실제 WebSocket 연결: 허용하지 않은 Origin은 handshake 실패")
  void rejectsUnknownOrigin() {
    assertThatThrownBy(
            () ->
                connect(
                    "http://evil.example", "Bearer " + jwtTokenService.issueAccessToken(USER_ID)))
        .isInstanceOf(Exception.class);
  }

  /** 설정값이 실제 STOMP decoder와 Simple Broker까지 전달됐는지 Spring 런타임 객체에서 확인한다. */
  @Test
  @DisplayName("실제 STOMP 설정: 64 KiB frame과 양방향 10초 heartbeat를 적용한다")
  void appliesTransportAndHeartbeatLimits() {
    StompSubProtocolHandler stompHandler =
        subProtocolWebSocketHandler.getProtocolHandlerMap().values().stream()
            .filter(StompSubProtocolHandler.class::isInstance)
            .map(StompSubProtocolHandler.class::cast)
            .findFirst()
            .orElseThrow();

    assertThat(stompHandler.getMessageSizeLimit()).isEqualTo(65_536);
    assertThat(stompHandler.isPreserveReceiveOrder()).isTrue();
    assertThat(subProtocolWebSocketHandler.getTimeToFirstMessage()).isEqualTo(10_000);
    assertThat(simpleBrokerMessageHandler.getHeartbeatValue()).containsExactly(10_000L, 10_000L);
    assertThat(simpleBrokerMessageHandler.getTaskScheduler()).isSameAs(chatWebSocketTaskScheduler);
  }

  /** CONNECT 때 유효했던 짧은 JWT가 만료되면 추가 frame이 없어도 서버 scheduler가 실제 socket을 닫는다. */
  @Test
  @DisplayName("실제 STOMP 연결: JWT 만료 시 idle WebSocket도 종료한다")
  void closesIdleConnectionWhenJwtExpires() throws Exception {
    given(userAccountService.getAccount(USER_ID))
        .willReturn(new UserAccountView(USER_ID, "ACTIVE", "User", "user@example.com"));

    StompSession session =
        connect("http://allowed.test", "Bearer " + jwtTokenService.issueAccessToken(USER_ID));

    assertThat(session.isConnected()).isTrue();
    assertThat(waitUntilDisconnected(session, 8, TimeUnit.SECONDS)).isTrue();
  }

  /** 개인 control queue를 먼저 구독한 뒤 ping을 보내면 같은 UUID의 PONG이 실제 socket으로 돌아온다. */
  @Test
  @DisplayName("실제 STOMP 제어 흐름: control queue 구독 뒤 PING에 PONG을 받는다")
  void receivesPongOnOriginSession() throws Exception {
    given(userAccountService.getAccount(USER_ID))
        .willReturn(new UserAccountView(USER_ID, "ACTIVE", "User", "user@example.com"));
    StompSession session =
        connect("http://allowed.test", "Bearer " + jwtTokenService.issueAccessToken(USER_ID));
    LinkedBlockingQueue<ChatControlEventPayload> events = subscribeControl(session);
    UUID requestId = UUID.randomUUID();

    session.send(
        ChatStompDestinations.CONTROL_SEND,
        new ChatControlPingPayload(ChatControlPingPayload.CURRENT_VERSION, requestId));

    ChatControlEventPayload pong = events.poll(2, TimeUnit.SECONDS);
    assertThat(pong).isNotNull();
    assertThat(pong.eventType()).isEqualTo(ChatStompEventType.PONG);
    assertThat(pong.requestId()).isEqualTo(requestId);
    session.disconnect();
  }

  /** broker 구독 완료 뒤 DB의 사용자별 마지막 메시지 번호가 SUBSCRIPTION_READY로 도착하는지 끝까지 검증한다. */
  @Test
  @DisplayName("실제 STOMP 구독 흐름: 참여자는 room SUBSCRIBE 뒤 준비 완료를 받는다")
  void receivesSubscriptionReadyAfterRoomTopicRegistration() throws Exception {
    long roomId = 556L;
    long highWatermark = 700L;
    given(userAccountService.getAccount(USER_ID))
        .willReturn(new UserAccountView(USER_ID, "ACTIVE", "User", "user@example.com"));
    given(memberRepository.findByChatRoomIdAndUserId(roomId, USER_ID))
        .willReturn(
            Optional.of(
                ChatRoomMember.builder()
                    .chatRoomId(roomId)
                    .userId(USER_ID)
                    .historyHiddenThroughMessageId(0L)
                    .build()));
    given(chatRoomRepository.findById(roomId))
        .willReturn(
            Optional.of(ChatRoom.builder().id(roomId).lastMessageId(highWatermark).build()));

    StompSession session =
        connect("http://allowed.test", "Bearer " + jwtTokenService.issueAccessToken(USER_ID));
    LinkedBlockingQueue<ChatControlEventPayload> events = subscribeControl(session);
    UUID requestId = UUID.randomUUID();
    session.send(
        ChatStompDestinations.CONTROL_SEND,
        new ChatControlPingPayload(ChatControlPingPayload.CURRENT_VERSION, requestId));
    ChatControlEventPayload pong = events.poll(2, TimeUnit.SECONDS);
    assertThat(pong).isNotNull();
    assertThat(pong.eventType()).isEqualTo(ChatStompEventType.PONG);

    session.subscribe(ChatStompDestinations.roomTopic(roomId), new IgnoredRoomFrameHandler());

    ChatControlEventPayload ready = events.poll(2, TimeUnit.SECONDS);
    assertThat(ready).isNotNull();
    assertThat(ready.eventType()).isEqualTo(ChatStompEventType.SUBSCRIPTION_READY);
    assertThat(ready.roomId()).isEqualTo(roomId);
    assertThat(ready.highWatermark()).isEqualTo(highWatermark);
    session.disconnect();
  }

  /** 빠른 화면 이동으로 SUBSCRIBE 직후 UNSUBSCRIBE해도 정상 해제를 broker 장애로 오인해 socket을 닫으면 안 된다. */
  @Test
  @DisplayName("실제 STOMP 구독 해제: 빠른 SUBSCRIBE·UNSUBSCRIBE 뒤에도 연결을 유지한다")
  void keepsConnectionAfterImmediateUnsubscribe() throws Exception {
    long roomId = 557L;
    given(userAccountService.getAccount(USER_ID))
        .willReturn(new UserAccountView(USER_ID, "ACTIVE", "User", "user@example.com"));
    given(memberRepository.findByChatRoomIdAndUserId(roomId, USER_ID))
        .willReturn(
            Optional.of(
                ChatRoomMember.builder()
                    .chatRoomId(roomId)
                    .userId(USER_ID)
                    .historyHiddenThroughMessageId(0L)
                    .build()));
    given(chatRoomRepository.findById(roomId))
        .willReturn(Optional.of(ChatRoom.builder().id(roomId).lastMessageId(701L).build()));

    StompSession session =
        connect("http://allowed.test", "Bearer " + jwtTokenService.issueAccessToken(USER_ID));
    StompSession.Subscription subscription =
        session.subscribe(ChatStompDestinations.roomTopic(roomId), new IgnoredRoomFrameHandler());
    subscription.unsubscribe();

    await()
        .during(Duration.ofMillis(300))
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(
            () -> {
              assertThat(session.isConnected()).isTrue();
              assertThat(findBrokerSubscriptions(ChatStompDestinations.roomTopic(roomId)))
                  .isEmpty();
            });
    session.disconnect();
  }

  /** roomId를 추측해도 참여자 행이 없는 사용자의 구독은 broker에 등록되지 않는다. */
  @Test
  @DisplayName("실제 STOMP 구독 권한: 비참여자는 room topic을 구독하지 못한다")
  void rejectsRoomSubscriptionFromOutsider() throws Exception {
    long roomId = 999_001L;
    given(userAccountService.getAccount(USER_ID))
        .willReturn(new UserAccountView(USER_ID, "ACTIVE", "User", "user@example.com"));
    given(memberRepository.findByChatRoomIdAndUserId(roomId, USER_ID)).willReturn(Optional.empty());
    StompSession session =
        connect("http://allowed.test", "Bearer " + jwtTokenService.issueAccessToken(USER_ID));

    session.subscribe(ChatStompDestinations.roomTopic(roomId), new IgnoredRoomFrameHandler());
    verify(memberRepository, timeout(2_000)).findByChatRoomIdAndUserId(roomId, USER_ID);

    // 비동기 broker 처리가 뒤늦게 실행돼도 이 topic에는 어떤 subscription도 생기지 않아야 한다.
    await()
        .during(Duration.ofMillis(300))
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(
            () ->
                assertThat(findBrokerSubscriptions(ChatStompDestinations.roomTopic(roomId)))
                    .isEmpty());
    session.disconnect();
  }

  /** 같은 계정이 두 기기로 연결돼도 A의 구독 준비 응답이 B로 새지 않는지 실제 user destination에서 확인한다. */
  @Test
  @DisplayName("실제 STOMP 개인 응답: SUBSCRIPTION_READY는 원래 session만 받는다")
  void sendsReadyOnlyToOriginSession() throws Exception {
    long roomId = 556L;
    long highWatermark = 700L;
    given(userAccountService.getAccount(USER_ID))
        .willReturn(new UserAccountView(USER_ID, "ACTIVE", "User", "user@example.com"));
    given(memberRepository.findByChatRoomIdAndUserId(roomId, USER_ID))
        .willReturn(
            Optional.of(
                ChatRoomMember.builder()
                    .chatRoomId(roomId)
                    .userId(USER_ID)
                    .historyHiddenThroughMessageId(0L)
                    .build()));
    given(chatRoomRepository.findById(roomId))
        .willReturn(
            Optional.of(ChatRoom.builder().id(roomId).lastMessageId(highWatermark).build()));

    StompSession sessionA =
        connect("http://allowed.test", "Bearer " + jwtTokenService.issueAccessToken(USER_ID));
    StompSession sessionB =
        connect("http://allowed.test", "Bearer " + jwtTokenService.issueAccessToken(USER_ID));
    LinkedBlockingQueue<ChatControlEventPayload> eventsA = subscribeControl(sessionA);
    LinkedBlockingQueue<ChatControlEventPayload> eventsB = subscribeControl(sessionB);

    // 두 session 모두 PONG을 받은 뒤라 control queue 미등록 때문에 B가 못 받는 거짓 성공이 아니다.
    sendPingAndAwaitPong(sessionA, eventsA);
    sendPingAndAwaitPong(sessionB, eventsB);
    sessionA.subscribe(ChatStompDestinations.roomTopic(roomId), new IgnoredRoomFrameHandler());

    ChatControlEventPayload readyA = eventsA.poll(2, TimeUnit.SECONDS);
    assertThat(readyA).isNotNull();
    assertThat(readyA.eventType()).isEqualTo(ChatStompEventType.SUBSCRIPTION_READY);
    assertThat(eventsB.poll(300, TimeUnit.MILLISECONDS)).isNull();
    sessionA.disconnect();
    sessionB.disconnect();
  }

  /** 두 참여자의 실제 socket이 신규 TEXT 원문을 받고 원래 발신 session만 저장 ACK를 받는지 검증한다. */
  @Test
  @DisplayName("실제 STOMP TEXT: 두 참여자는 원문을 받고 발신 session만 ACK를 받는다")
  void sendsCommittedTextToRoomAndAckToOriginSession() throws Exception {
    long roomId = 560L;
    prepareVisibleRoomAccess(roomId, USER_ID, RECIPIENT_ID);

    StompSession senderSession =
        connect("http://allowed.test", "Bearer " + jwtTokenService.issueAccessToken(USER_ID));
    StompSession recipientSession =
        connect("http://allowed.test", "Bearer " + jwtTokenService.issueAccessToken(RECIPIENT_ID));

    LinkedBlockingQueue<ChatMessageAckPayload> senderAcks = subscribeAcks(senderSession);
    LinkedBlockingQueue<ChatMessageAckPayload> recipientAcks = subscribeAcks(recipientSession);
    LinkedBlockingQueue<ChatMessageErrorPayload> senderErrors = subscribeErrors(senderSession);
    LinkedBlockingQueue<ChatMessageCreatedPayload> senderMessages =
        subscribeReadyRoom(senderSession, roomId);
    LinkedBlockingQueue<ChatMessageCreatedPayload> recipientMessages =
        subscribeReadyRoom(recipientSession, roomId);

    UUID clientMessageId = UUID.randomUUID();
    Instant sentAt = Instant.parse("2026-08-21T10:15:30Z");
    Message saved = textMessage(roomId, USER_ID, 701L, clientMessageId, "안녕하세요", sentAt);
    given(chatTextMessageService.saveText(roomId, USER_ID, clientMessageId, "안녕하세요"))
        .willReturn(new TextMessageSaveResult(saved, false, RECIPIENT_ID, false));

    senderSession.send(
        ChatStompDestinations.messageSend(roomId),
        new ChatMessageSendPayload(clientMessageId, "안녕하세요"));

    // 먼저 서버 handler가 SEND를 실제로 처리했는지 확인한 뒤 broker의 비동기 결과를 기다린다.
    verify(chatTextMessageService, timeout(2_000))
        .saveText(roomId, USER_ID, clientMessageId, "안녕하세요");
    assertThat(senderErrors.poll(300, TimeUnit.MILLISECONDS)).isNull();
    assertThat(clientFrameErrors).isEmpty();
    ChatMessageCreatedPayload senderMessage = senderMessages.poll(2, TimeUnit.SECONDS);
    ChatMessageCreatedPayload recipientMessage = recipientMessages.poll(2, TimeUnit.SECONDS);
    ChatMessageAckPayload ack = senderAcks.poll(2, TimeUnit.SECONDS);
    assertThat(senderMessage).isNotNull();
    assertThat(recipientMessage).isNotNull();
    assertThat(senderMessage.messageId()).isEqualTo(701L);
    assertThat(recipientMessage.originalContent()).isEqualTo("안녕하세요");
    assertThat(ack).isNotNull();
    assertThat(ack.clientMessageId()).isEqualTo(clientMessageId);
    assertThat(ack.messageId()).isEqualTo(701L);
    assertThat(ack.duplicate()).isFalse();
    assertThat(recipientAcks.poll(300, TimeUnit.MILLISECONDS)).isNull();
    senderSession.disconnect();
    recipientSession.disconnect();
  }

  /** 중복 저장 결과는 ACK만 다시 보내고 상대의 room topic에는 같은 말풍선을 재발행하지 않는다. */
  @Test
  @DisplayName("실제 STOMP TEXT: 중복 재전송은 duplicate ACK만 보내고 재방송하지 않는다")
  void sendsOnlyDuplicateAckForRetry() throws Exception {
    long roomId = 561L;
    prepareVisibleRoomAccess(roomId, USER_ID, RECIPIENT_ID);
    StompSession session =
        connect("http://allowed.test", "Bearer " + jwtTokenService.issueAccessToken(USER_ID));
    LinkedBlockingQueue<ChatMessageAckPayload> acks = subscribeAcks(session);
    LinkedBlockingQueue<ChatMessageErrorPayload> errors = subscribeErrors(session);
    LinkedBlockingQueue<ChatMessageCreatedPayload> roomMessages =
        subscribeReadyRoom(session, roomId);

    UUID clientMessageId = UUID.randomUUID();
    Message existing =
        textMessage(
            roomId,
            USER_ID,
            702L,
            clientMessageId,
            "이미 저장된 메시지",
            Instant.parse("2026-08-21T10:16:30Z"));
    given(chatTextMessageService.saveText(roomId, USER_ID, clientMessageId, "이미 저장된 메시지"))
        .willReturn(new TextMessageSaveResult(existing, true, RECIPIENT_ID, false));

    session.send(
        ChatStompDestinations.messageSend(roomId),
        new ChatMessageSendPayload(clientMessageId, "이미 저장된 메시지"));

    // 중복 재전송도 같은 저장 서비스까지 도달해야 기존 저장 결과를 ACK로 돌려줄 수 있다.
    verify(chatTextMessageService, timeout(2_000))
        .saveText(roomId, USER_ID, clientMessageId, "이미 저장된 메시지");
    assertThat(errors.poll(300, TimeUnit.MILLISECONDS)).isNull();
    ChatMessageAckPayload ack = acks.poll(2, TimeUnit.SECONDS);
    assertThat(ack).isNotNull();
    assertThat(ack.messageId()).isEqualTo(702L);
    assertThat(ack.duplicate()).isTrue();
    assertThat(roomMessages.poll(300, TimeUnit.MILLISECONDS)).isNull();
    session.disconnect();
  }

  /** 신규 BOOKING_CARD가 실제 Simple Broker를 지나 두 참여자의 socket에 같은 구조로 도착하는지 검증한다. */
  @Test
  @DisplayName("실제 STOMP BOOKING_CARD: 두 참여자는 서버 생성 신청 카드를 실시간으로 받는다")
  void sendsBookingCardToBothParticipants() throws Exception {
    long roomId = 563L;
    prepareVisibleRoomAccess(roomId, USER_ID, RECIPIENT_ID);

    StompSession tenantSession =
        connect("http://allowed.test", "Bearer " + jwtTokenService.issueAccessToken(USER_ID));
    StompSession landlordSession =
        connect("http://allowed.test", "Bearer " + jwtTokenService.issueAccessToken(RECIPIENT_ID));
    LinkedBlockingQueue<ChatMessageCreatedPayload> tenantMessages =
        subscribeReadyRoom(tenantSession, roomId);
    LinkedBlockingQueue<ChatMessageCreatedPayload> landlordMessages =
        subscribeReadyRoom(landlordSession, roomId);

    Message card = bookingCardMessage(roomId);
    realtimeMessagePublisher.publishNewCard(
        new BookingCardService.ProcessResult(
            roomId,
            false,
            card,
            true,
            List.of(
                new BookingCardWriter.MemberActivityResult(USER_ID, true, false),
                new BookingCardWriter.MemberActivityResult(RECIPIENT_ID, true, false))));

    ChatMessageCreatedPayload tenantCard = tenantMessages.poll(2, TimeUnit.SECONDS);
    ChatMessageCreatedPayload landlordCard = landlordMessages.poll(2, TimeUnit.SECONDS);
    assertThat(tenantCard).isNotNull();
    assertThat(landlordCard).isNotNull();
    assertThat(tenantCard.type()).isEqualTo(MessageType.BOOKING_CARD);
    assertThat(tenantCard.originalContent()).isNull();
    assertThat(tenantCard.bookingCard().bookingId()).isEqualTo(9001L);
    assertThat(landlordCard.bookingCard()).isEqualTo(tenantCard.bookingCard());

    tenantSession.disconnect();
    landlordSession.disconnect();
  }

  /** 같은 UUID의 본문 충돌은 socket을 닫지 않고 발신 session 오류 queue로만 전달한다. */
  @Test
  @DisplayName("실제 STOMP TEXT: 멱등 본문 충돌은 개인 오류 이벤트로 응답한다")
  void sendsConflictErrorOnlyToOriginSession() throws Exception {
    long roomId = 562L;
    prepareVisibleRoomAccess(roomId, USER_ID, RECIPIENT_ID);
    given(userAccountService.getLanguage(USER_ID)).willReturn("ko");
    StompSession session =
        connect("http://allowed.test", "Bearer " + jwtTokenService.issueAccessToken(USER_ID));
    LinkedBlockingQueue<ChatMessageErrorPayload> errors = subscribeErrors(session);
    subscribeReadyRoom(session, roomId);

    UUID clientMessageId = UUID.randomUUID();
    given(chatTextMessageService.saveText(roomId, USER_ID, clientMessageId, "바뀐 본문"))
        .willThrow(new ChatClientMessageConflictException());

    session.send(
        ChatStompDestinations.messageSend(roomId),
        new ChatMessageSendPayload(clientMessageId, "바뀐 본문"));

    ChatMessageErrorPayload error = errors.poll(2, TimeUnit.SECONDS);
    assertThat(error).isNotNull();
    assertThat(error.clientMessageId()).isEqualTo(clientMessageId);
    assertThat(error.code()).isEqualTo("CHAT_CLIENT_MESSAGE_CONFLICT");
    assertThat(error.message()).isEqualTo("같은 메시지 ID를 다른 본문에 사용할 수 없습니다.");
    assertThat(session.isConnected()).isTrue();
    session.disconnect();
  }

  /** handshake header와 CONNECT header를 분리해 실제 앱과 같은 순서로 접속한다. */
  private StompSession connect(String origin, String authorization) throws Exception {
    WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
    handshakeHeaders.setOrigin(origin);

    StompHeaders connectHeaders = new StompHeaders();
    connectHeaders.add(HttpHeaders.AUTHORIZATION, authorization);

    String url = "ws://127.0.0.1:" + port + "/ws/chat";
    return stompClient
        .connectAsync(
            url,
            handshakeHeaders,
            connectHeaders,
            new StompSessionHandlerAdapter() {
              @Override
              public void handleException(
                  StompSession session,
                  StompCommand command,
                  StompHeaders headers,
                  byte[] payload,
                  Throwable exception) {
                clientFrameErrors.offer(exception);
              }
            })
        .get(5, TimeUnit.SECONDS);
  }

  /** control queue의 JSON body를 타입 안전한 record로 모으는 실제 client 구독을 만든다. */
  private static LinkedBlockingQueue<ChatControlEventPayload> subscribeControl(
      StompSession session) {
    LinkedBlockingQueue<ChatControlEventPayload> events = new LinkedBlockingQueue<>();
    session.subscribe(ChatStompDestinations.CONTROL_QUEUE, new ControlEventFrameHandler(events));
    return events;
  }

  /** 발신 session 전용 ACK queue의 JSON을 타입 안전하게 모은다. */
  private static LinkedBlockingQueue<ChatMessageAckPayload> subscribeAcks(StompSession session) {
    LinkedBlockingQueue<ChatMessageAckPayload> events = new LinkedBlockingQueue<>();
    session.subscribe(ChatStompDestinations.ACK_QUEUE, new AckFrameHandler(events));
    return events;
  }

  /** 발신 session 전용 오류 queue의 JSON을 타입 안전하게 모은다. */
  private static LinkedBlockingQueue<ChatMessageErrorPayload> subscribeErrors(
      StompSession session) {
    LinkedBlockingQueue<ChatMessageErrorPayload> events = new LinkedBlockingQueue<>();
    session.subscribe(ChatStompDestinations.ERROR_QUEUE, new ErrorFrameHandler(events));
    return events;
  }

  /** 개인 control 통로를 먼저 확인하고 room topic 등록 READY까지 마친 뒤 저장 완료 payload queue를 반환한다. */
  private static LinkedBlockingQueue<ChatMessageCreatedPayload> subscribeReadyRoom(
      StompSession session, long roomId) throws InterruptedException {
    LinkedBlockingQueue<ChatControlEventPayload> controlEvents = subscribeControl(session);
    sendPingAndAwaitPong(session, controlEvents);

    LinkedBlockingQueue<ChatMessageCreatedPayload> events = new LinkedBlockingQueue<>();
    session.subscribe(
        ChatStompDestinations.roomTopic(roomId), new MessageCreatedFrameHandler(events));
    awaitRoomReady(controlEvents, roomId);
    return events;
  }

  /** room SUBSCRIBE가 실제 broker에 등록됐다는 READY를 기다린다. */
  private static void awaitRoomReady(
      LinkedBlockingQueue<ChatControlEventPayload> controlEvents, long roomId)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (System.nanoTime() < deadline) {
      ChatControlEventPayload event = controlEvents.poll(100, TimeUnit.MILLISECONDS);
      if (event != null
          && event.eventType() == ChatStompEventType.SUBSCRIPTION_READY
          && Long.valueOf(roomId).equals(event.roomId())) {
        return;
      }
    }
    throw new AssertionError("SUBSCRIPTION_READY를 받지 못했습니다: roomId=" + roomId);
  }

  /** 두 사용자 모두 같은 방을 볼 수 있도록 인증·참여자·마지막 메시지 mock을 준비한다. */
  private void prepareVisibleRoomAccess(long roomId, long senderId, long recipientId) {
    given(userAccountService.getAccount(senderId))
        .willReturn(new UserAccountView(senderId, "ACTIVE", "Sender", "sender@example.com"));
    given(userAccountService.getAccount(recipientId))
        .willReturn(
            new UserAccountView(recipientId, "ACTIVE", "Recipient", "recipient@example.com"));
    given(memberRepository.findByChatRoomIdAndUserId(roomId, senderId))
        .willReturn(
            Optional.of(
                ChatRoomMember.builder()
                    .chatRoomId(roomId)
                    .userId(senderId)
                    .counterpartId(recipientId)
                    .historyHiddenThroughMessageId(0L)
                    .build()));
    given(memberRepository.findByChatRoomIdAndUserId(roomId, recipientId))
        .willReturn(
            Optional.of(
                ChatRoomMember.builder()
                    .chatRoomId(roomId)
                    .userId(recipientId)
                    .counterpartId(senderId)
                    .historyHiddenThroughMessageId(0L)
                    .build()));
    given(chatRoomRepository.findById(roomId))
        .willReturn(Optional.of(ChatRoom.builder().id(roomId).lastMessageId(700L).build()));
  }

  /** STOMP publisher가 받을 저장 완료 TEXT fixture다. */
  private static Message textMessage(
      long roomId,
      long senderId,
      long messageId,
      UUID clientMessageId,
      String content,
      Instant sentAt) {
    return Message.builder()
        .id(messageId)
        .chatRoomId(roomId)
        .senderId(senderId)
        .type(MessageType.TEXT)
        .content(content)
        .clientMessageId(clientMessageId)
        .sentAt(sentAt)
        .build();
  }

  /** 실제 STOMP 변환에서 JSON 카드 구조를 확인하기 위한 서버 저장 완료 메시지다. */
  private static Message bookingCardMessage(long roomId) {
    BookingCardPayload payload =
        new BookingCardPayload(
            9001L,
            new BookingCardPayload.Listing(
                "listing-1",
                "https://cdn.kohere.com/room.jpg",
                "Hongdae Studio share",
                "Seogyo-dong, Mapo-gu",
                420_000),
            new BookingCardPayload.Applicant(
                USER_ID, "Gil dong Hong", "MALE", "MN", "Mongolia", "tenant@example.com"),
            "offer-1",
            "Room A",
            LocalDate.of(2026, 6, 15),
            3,
            0,
            1_260_000);

    return Message.builder()
        .id(703L)
        .chatRoomId(roomId)
        .type(MessageType.BOOKING_CARD)
        .bookingId(payload.bookingId())
        .payload(payload)
        .sentAt(Instant.parse("2026-08-21T10:17:30Z"))
        .build();
  }

  /** 한 session의 개인 queue 등록을 실제 PING/PONG 왕복으로 확인한다. */
  private static void sendPingAndAwaitPong(
      StompSession session, LinkedBlockingQueue<ChatControlEventPayload> events)
      throws InterruptedException {
    UUID requestId = UUID.randomUUID();
    session.send(
        ChatStompDestinations.CONTROL_SEND,
        new ChatControlPingPayload(ChatControlPingPayload.CURRENT_VERSION, requestId));
    ChatControlEventPayload pong = events.poll(2, TimeUnit.SECONDS);
    assertThat(pong).isNotNull();
    assertThat(pong.eventType()).isEqualTo(ChatStompEventType.PONG);
    assertThat(pong.requestId()).isEqualTo(requestId);
  }

  /** 비동기 transport close가 client 상태에 반영될 때까지 짧게 확인한다. 최대 대기 뒤에는 실패한다. */
  private static boolean waitUntilDisconnected(StompSession session, long timeout, TimeUnit unit)
      throws InterruptedException {
    long deadline = System.nanoTime() + unit.toNanos(timeout);
    while (session.isConnected() && System.nanoTime() < deadline) {
      Thread.sleep(25);
    }
    return !session.isConnected();
  }

  /** Simple Broker 내부 registry를 실제 MESSAGE destination과 같은 방식으로 조회한다. */
  private org.springframework.util.MultiValueMap<String, String> findBrokerSubscriptions(
      String destination) {
    SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
    headers.setDestination(destination);
    headers.setLeaveMutable(true);
    var probe = MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    return simpleBrokerMessageHandler.getSubscriptionRegistry().findSubscriptions(probe);
  }

  /** 테스트에서만 CONNECTED 이벤트의 서버 Principal을 관찰하는 작은 기록기다. */
  static final class ConnectedUserRecorder implements ApplicationListener<SessionConnectedEvent> {

    private final LinkedBlockingQueue<Principal> users = new LinkedBlockingQueue<>();

    @Override
    public void onApplicationEvent(SessionConnectedEvent event) {
      if (event.getUser() != null) {
        users.offer(event.getUser());
      }
    }

    Principal await() throws InterruptedException {
      return users.poll(5, TimeUnit.SECONDS);
    }

    void clear() {
      users.clear();
    }
  }

  /** 비동기 STOMP control frame을 테스트 thread가 기다릴 수 있는 queue에 넣는다. */
  private record ControlEventFrameHandler(LinkedBlockingQueue<ChatControlEventPayload> events)
      implements StompFrameHandler {

    @Override
    public Type getPayloadType(StompHeaders headers) {
      return ChatControlEventPayload.class;
    }

    @Override
    public void handleFrame(StompHeaders headers, Object payload) {
      events.offer((ChatControlEventPayload) payload);
    }
  }

  /** 비동기 ACK frame을 테스트 queue에 넣는다. */
  private record AckFrameHandler(LinkedBlockingQueue<ChatMessageAckPayload> events)
      implements StompFrameHandler {

    @Override
    public Type getPayloadType(StompHeaders headers) {
      return ChatMessageAckPayload.class;
    }

    @Override
    public void handleFrame(StompHeaders headers, Object payload) {
      events.offer((ChatMessageAckPayload) payload);
    }
  }

  /** 비동기 TEXT 저장 완료 frame을 테스트 queue에 넣는다. */
  private record MessageCreatedFrameHandler(LinkedBlockingQueue<ChatMessageCreatedPayload> events)
      implements StompFrameHandler {

    @Override
    public Type getPayloadType(StompHeaders headers) {
      return ChatMessageCreatedPayload.class;
    }

    @Override
    public void handleFrame(StompHeaders headers, Object payload) {
      events.offer((ChatMessageCreatedPayload) payload);
    }
  }

  /** 비동기 개별 SEND 오류 frame을 테스트 queue에 넣는다. */
  private record ErrorFrameHandler(LinkedBlockingQueue<ChatMessageErrorPayload> events)
      implements StompFrameHandler {

    @Override
    public Type getPayloadType(StompHeaders headers) {
      return ChatMessageErrorPayload.class;
    }

    @Override
    public void handleFrame(StompHeaders headers, Object payload) {
      events.offer((ChatMessageErrorPayload) payload);
    }
  }

  /** room topic payload는 이 단계에서 발행하지 않으므로 구독 등록 자체만 확인하는 빈 handler다. */
  private static final class IgnoredRoomFrameHandler implements StompFrameHandler {

    @Override
    public Type getPayloadType(StompHeaders headers) {
      return byte[].class;
    }

    @Override
    public void handleFrame(StompHeaders headers, Object payload) {
      // 2단계는 구독 준비만 검증한다. 실제 TEXT/BOOKING_CARD 수신은 다음 단계 테스트에서 다룬다.
    }
  }

  /** 운영 코드에 테스트 관찰용 listener를 넣지 않도록 테스트 컨텍스트에서만 빈을 등록한다. */
  @TestConfiguration(proxyBeanMethods = false)
  static class Events {

    @Bean
    ConnectedUserRecorder connectedUserRecorder() {
      return new ConnectedUserRecorder();
    }
  }
}
