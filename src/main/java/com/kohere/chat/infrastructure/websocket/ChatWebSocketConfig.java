package com.kohere.chat.infrastructure.websocket;

import com.kohere.chat.presentation.stomp.ChatStompDestinations;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * 채팅 WebSocket 통로와 STOMP 경로 규칙을 Spring에 등록한다.
 *
 * <p>연결·broker 기반과 안전한 구독·TEXT 전송 통로를 연다. 개인 queue는 정해진 경로만, room topic과 TEXT SEND는 DB에서 현재 보이는
 * 참여자임을 확인한 경우만 허용한다. 저장·차단·멱등성은 handler가 호출하는 애플리케이션 서비스에서 다시 검증한다.
 */
@Configuration
@EnableWebSocketMessageBroker
public class ChatWebSocketConfig implements WebSocketMessageBrokerConfigurer {

  private static final String APPLICATION_PREFIX = "/app";
  private static final String USER_PREFIX = "/user";
  private static final String TOPIC_PREFIX = "/topic";
  private static final String QUEUE_PREFIX = "/queue";

  private final ChatWebSocketProperties properties;
  private final ChatWebSocketSessionDecoratorFactory sessionDecoratorFactory;
  private final ChatStompConnectErrorHandler connectErrorHandler;
  private final TaskScheduler taskScheduler;

  /** scheduler bean을 별도 설정에서 주입해 config 자체가 자기 @Bean을 요구하는 순환 생성을 피한다. */
  public ChatWebSocketConfig(
      ChatWebSocketProperties properties,
      ChatWebSocketSessionDecoratorFactory sessionDecoratorFactory,
      ChatStompConnectErrorHandler connectErrorHandler,
      @Qualifier("chatWebSocketTaskScheduler") TaskScheduler taskScheduler) {
    this.properties = properties;
    this.sessionDecoratorFactory = sessionDecoratorFactory;
    this.connectErrorHandler = connectErrorHandler;
    this.taskScheduler = taskScheduler;
  }

  /**
   * 클라이언트가 처음 접속하는 HTTP handshake 주소를 등록한다.
   *
   * <p>허용 Origin 목록이 비어 있으면 {@code setAllowedOrigins}를 호출하지 않아 Spring의 같은 Origin 기본 정책을 유지한다. 외부
   * Origin을 허용할 때도 정확한 주소만 받고 와일드카드는 거부한다.
   */
  @Override
  public void registerStompEndpoints(StompEndpointRegistry registry) {
    String[] allowedOrigins = properties.allowedOriginsArray();
    rejectWildcardOrigin(allowedOrigins);

    var endpoint = registry.addEndpoint(ChatStompDestinations.HANDSHAKE_ENDPOINT);
    if (allowedOrigins.length > 0) {
      endpoint.setAllowedOrigins(allowedOrigins);
    }

    // 같은 session의 SUBSCRIBE 다음 PING/SEND가 먼저 처리되는 역전을 막아 준비 응답 유실을 방지한다.
    registry.setPreserveReceiveOrder(true);

    // CONNECT 처리 중 발생한 인증 예외를 내부 stack trace가 아닌 안전한 STOMP ERROR body로 바꾼다.
    registry.setErrorHandler(connectErrorHandler);
  }

  /**
   * application·broker·개인 destination prefix와 Simple Broker heartbeat를 설정한다.
   *
   * <p>{@code /app}은 서버 handler로 들어오는 경로, {@code /topic}/{@code /queue}는 broker가 전달하는 경로, {@code
   * /user}는 사용자별 개인 경로다. 현재는 단일 JVM Simple Broker를 사용한다.
   */
  @Override
  public void configureMessageBroker(MessageBrokerRegistry registry) {
    long heartbeatMillis = properties.getHeartbeatInterval().toMillis();

    registry.setApplicationDestinationPrefixes(APPLICATION_PREFIX);
    registry.setUserDestinationPrefix(USER_PREFIX);
    registry
        .enableSimpleBroker(TOPIC_PREFIX, QUEUE_PREFIX)
        .setTaskScheduler(taskScheduler)
        .setHeartbeatValue(new long[] {heartbeatMillis, heartbeatMillis});
  }

  /** STOMP frame 전체 상한과 첫 frame 제한을 적용하고 실제 socket session 추적 decorator를 연결한다. */
  @Override
  public void configureWebSocketTransport(WebSocketTransportRegistration registry) {
    registry
        .setMessageSizeLimit(properties.getMessageSizeLimitBytes())
        .setTimeToFirstMessage(Math.toIntExact(properties.getTimeToFirstMessage().toMillis()))
        .addDecoratorFactory(sessionDecoratorFactory);
  }

  /** 운영에서 모든 사이트를 허용하는 실수를 애플리케이션 시작 시 바로 실패시킨다. */
  private static void rejectWildcardOrigin(String[] allowedOrigins) {
    if (Arrays.asList(allowedOrigins).contains("*")) {
      throw new IllegalStateException(
          "app.chat.websocket.allowed-origins must not contain wildcard '*'");
    }
  }
}
