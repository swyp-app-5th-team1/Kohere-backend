package com.kohere.chat.infrastructure.websocket;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * 클라이언트 inbound STOMP frame의 interceptor 순서를 고정한다.
 *
 * <p>HTTP handshake는 통로만 열기 때문에 실제 사용자는 첫 STOMP CONNECT frame에서 JWT로 인증해야 한다. 향후 destination 권한
 * interceptor가 추가돼도 이 인증이 항상 먼저 실행되도록 공식 권장 순서를 사용한다.
 */
@Configuration
@Order(Ordered.HIGHEST_PRECEDENCE + 99)
@RequiredArgsConstructor
public class ChatStompAuthenticationConfig implements WebSocketMessageBrokerConfigurer {

  private final StompJwtAuthenticationInterceptor authenticationInterceptor;

  /** CONNECT와 이후 frame에서 JWT 인증·만료 검사가 가장 먼저 실행되도록 등록한다. */
  @Override
  public void configureClientInboundChannel(ChannelRegistration registration) {
    registration.interceptors(authenticationInterceptor);
  }
}
