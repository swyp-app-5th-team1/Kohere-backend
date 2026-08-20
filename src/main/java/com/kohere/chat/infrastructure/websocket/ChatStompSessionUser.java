package com.kohere.chat.infrastructure.websocket;

import com.kohere.common.exception.ErrorCode;
import java.security.Principal;
import java.util.Map;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.security.core.Authentication;

/** STOMP frame에서 1단계 CONNECT가 검증해 둔 사용자 번호를 안전하게 꺼내는 공통 도우미다. */
public final class ChatStompSessionUser {

  private static final String REQUIRED_ROLE = "ROLE_USER";

  private ChatStompSessionUser() {}

  /**
   * Spring Principal과 WebSocket session 속성이 같은 인증 사용자를 가리키는지 확인한다.
   *
   * <p>클라이언트 payload의 userId는 신뢰하지 않는다. CONNECT JWT에서 만든 Authentication과 서버 session 속성 두 값을 비교해 이후
   * 권한 검사가 항상 같은 사용자 번호를 사용하게 한다.
   */
  public static long requireUserId(StompHeaderAccessor accessor) {
    Principal principal = accessor.getUser();
    if (!(principal instanceof Authentication authentication)
        || !authentication.isAuthenticated()
        || authentication.getAuthorities().stream()
            .noneMatch(authority -> REQUIRED_ROLE.equals(authority.getAuthority()))) {
      throw unauthenticated();
    }

    Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
    if (sessionAttributes == null) {
      throw unauthenticated();
    }

    Object storedUserId = sessionAttributes.get(StompJwtAuthenticationInterceptor.SESSION_USER_ID);
    if (!(storedUserId instanceof Long userId)
        || !String.valueOf(userId).equals(authentication.getName())) {
      throw unauthenticated();
    }
    return userId;
  }

  /** 개인 응답을 다른 기기가 아닌 요청한 socket 하나로 돌려보내기 위해 sessionId도 필수로 확인한다. */
  public static String requireSessionId(StompHeaderAccessor accessor) {
    String sessionId = accessor.getSessionId();
    if (sessionId == null || sessionId.isBlank()) {
      throw unauthenticated();
    }
    return sessionId;
  }

  private static ChatStompAuthenticationException unauthenticated() {
    return new ChatStompAuthenticationException(ErrorCode.UNAUTHENTICATED);
  }
}
