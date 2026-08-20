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
 * <p>사용자가 누구인지 모르는 상태에서는 채팅방 참여 권한을 검사할 수 없다. 따라서 JWT 인증을 먼저 실행하고, 그다음 deny-by-default destination
 * 권한 검사를 실행한다. 향후 Spring Security 메시지 인가가 추가돼도 이 설정이 먼저 동작하도록 공식 권장 순서를 사용한다.
 */
@Configuration
@Order(Ordered.HIGHEST_PRECEDENCE + 99)
@RequiredArgsConstructor
public class ChatStompAuthenticationConfig implements WebSocketMessageBrokerConfigurer {

  private final StompJwtAuthenticationInterceptor authenticationInterceptor;
  private final ChatStompAuthorizationInterceptor authorizationInterceptor;
  private final ChatSubscriptionReadyInterceptor subscriptionReadyInterceptor;

  /**
   * 등록 순서가 실행 순서이므로 JWT 인증 → destination 권한 검사 → broker 처리 완료 확인 순으로 둔다.
   *
   * <p>마지막 interceptor는 room SUBSCRIBE가 Simple Broker에 등록된 뒤에만 준비 완료 이벤트를 보내는 후처리 역할이다.
   */
  @Override
  public void configureClientInboundChannel(ChannelRegistration registration) {
    registration.interceptors(
        authenticationInterceptor, authorizationInterceptor, subscriptionReadyInterceptor);
  }
}
