package com.kohere.chat.infrastructure.websocket;

import com.kohere.common.exception.BusinessException;
import com.kohere.common.exception.ErrorCode;
import com.kohere.common.security.JwtTokenService;
import com.kohere.common.security.JwtVerificationResult;
import com.kohere.user.api.UserAccountService;
import com.kohere.user.api.UserAccountView;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

/**
 * STOMP CONNECT의 Bearer JWT를 검증해 WebSocket session 사용자를 등록한다.
 *
 * <p>Servlet의 {@code JwtAuthenticationFilter}는 HTTP 요청만 처리하므로 handshake가 WebSocket으로 전환된 뒤의 STOMP
 * frame에는 실행되지 않는다. 이 interceptor가 기존 {@link JwtTokenService}를 재사용해 같은 서명·issuer·만료 규칙을 적용한다.
 */
@Component
public class StompJwtAuthenticationInterceptor implements ChannelInterceptor {

  /** session 내부에서 검증된 JWT 만료 시각을 공유하는 key. token 원문은 저장하지 않는다. */
  static final String SESSION_EXPIRES_AT = "kohere.chat.jwt.expiresAt";

  /** session 내부에서 인증된 사용자 번호를 공유하는 key. */
  static final String SESSION_USER_ID = "kohere.chat.userId";

  private static final String BEARER_PREFIX = "Bearer ";
  private static final String ACTIVE_STATUS = "ACTIVE";

  private final JwtTokenService jwtTokenService;
  private final UserAccountService userAccountService;
  private final ChatWebSocketSessionManager sessionManager;

  public StompJwtAuthenticationInterceptor(
      JwtTokenService jwtTokenService,
      UserAccountService userAccountService,
      ChatWebSocketSessionManager sessionManager) {
    this.jwtTokenService = jwtTokenService;
    this.userAccountService = userAccountService;
    this.sessionManager = sessionManager;
  }

  /** CONNECT는 새로 인증하고, 이후 inbound frame은 session에 저장한 만료 시각을 한 번 더 확인한다. */
  @Override
  public Message<?> preSend(Message<?> message, MessageChannel channel) {
    StompHeaderAccessor accessor =
        StompHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
    if (accessor == null) {
      throw authenticationFailure(ErrorCode.UNAUTHENTICATED);
    }

    StompCommand command = accessor.getCommand();
    if (command == StompCommand.CONNECT || command == StompCommand.STOMP) {
      authenticateConnect(accessor);
      return message;
    }

    // heartbeat는 STOMP command가 없는 빈 frame이다. scheduler가 idle token을 닫으므로 여기서는 그대로 통과시킨다.
    if (command == null) {
      return message;
    }

    // transport가 자동 생성한 DISCONNECT는 인증 정보가 이미 정리된 뒤 도착할 수 있어 멱등하게 허용한다.
    if (command == StompCommand.DISCONNECT) {
      return message;
    }

    verifySessionHasNotExpired(accessor);
    return message;
  }

  /** Authorization header 하나를 검증하고 ACTIVE 사용자를 Spring Authentication으로 고정한다. */
  private void authenticateConnect(StompHeaderAccessor accessor) {
    String token = extractSingleBearerToken(accessor);
    JwtVerificationResult verification = verifyToken(token);

    if (!verification.principal().onboardingCompleted()) {
      throw authenticationFailure(ErrorCode.AUTH_ONBOARDING_REQUIRED);
    }

    long userId = verification.principal().userId();
    assertActiveAccount(userId);
    // JWT 검증 직후에는 유효했더라도 DB 계정 조회 중 만료될 수 있다. CONNECTED를 보내기 직전에 한 번 더 막는다.
    if (sessionManager.isExpired(verification.expiresAt(), Instant.now())) {
      throw authenticationFailure(ErrorCode.TOKEN_EXPIRED);
    }

    ChatStompPrincipal principal = new ChatStompPrincipal(userId);
    var authentication =
        new UsernamePasswordAuthenticationToken(
            principal, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));

    // Spring은 CONNECT에 설정된 사용자를 같은 session의 후속 SEND·SUBSCRIBE frame에 자동으로 연결한다.
    accessor.setUser(authentication);

    Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
    String sessionId = accessor.getSessionId();
    if (sessionAttributes == null || sessionId == null) {
      throw authenticationFailure(ErrorCode.UNAUTHENTICATED);
    }

    // 원본 JWT는 저장하지 않는다. 후속 권한 검사에 필요한 사용자 번호와 만료 시각만 남긴다.
    sessionAttributes.put(SESSION_USER_ID, userId);
    sessionAttributes.put(SESSION_EXPIRES_AT, verification.expiresAt());
    sessionManager.scheduleExpiration(sessionId, verification.expiresAt());
  }

  /** CONNECT에는 대소문자를 포함해 정확한 {@code Authorization: Bearer ...} header 하나만 허용한다. */
  private static String extractSingleBearerToken(StompHeaderAccessor accessor) {
    try {
      List<String> authorizationHeaders = accessor.getNativeHeader(HttpHeaders.AUTHORIZATION);
      if (authorizationHeaders == null || authorizationHeaders.size() != 1) {
        throw authenticationFailure(ErrorCode.UNAUTHENTICATED);
      }

      String header = authorizationHeaders.getFirst();
      if (header == null || !header.startsWith(BEARER_PREFIX)) {
        throw authenticationFailure(ErrorCode.UNAUTHENTICATED);
      }

      String token = header.substring(BEARER_PREFIX.length());
      if (token.isBlank()) {
        throw authenticationFailure(ErrorCode.UNAUTHENTICATED);
      }
      return token;
    } finally {
      // 이후 SessionConnectEvent나 DEBUG 로그가 전체 native header를 출력해도 JWT 원문이 남지 않게 즉시 지운다.
      accessor.removeNativeHeader(HttpHeaders.AUTHORIZATION);
    }
  }

  /** 기존 JWT 서비스의 모든 검증 예외를 STOMP에서 사용하는 안정적인 세 코드로 정규화한다. */
  private JwtVerificationResult verifyToken(String token) {
    try {
      return jwtTokenService.verify(token);
    } catch (ExpiredJwtException e) {
      throw authenticationFailure(ErrorCode.TOKEN_EXPIRED);
    } catch (JwtException | IllegalArgumentException e) {
      throw authenticationFailure(ErrorCode.UNAUTHENTICATED);
    }
  }

  /** 토큰 발급 뒤 탈퇴한 사용자까지 남은 토큰 시간 동안 접속하지 못하도록 현재 DB 상태를 확인한다. */
  private void assertActiveAccount(long userId) {
    try {
      UserAccountView account = userAccountService.getAccount(userId);
      if (!ACTIVE_STATUS.equals(account.status())) {
        throw authenticationFailure(ErrorCode.UNAUTHENTICATED);
      }
    } catch (BusinessException e) {
      // 사용자가 없거나 탈퇴했다는 상세 이유를 WebSocket 외부에 노출하지 않는다.
      throw authenticationFailure(ErrorCode.UNAUTHENTICATED);
    }
  }

  /** scheduler와 경합해 만료 직후 도착한 기능 frame도 처리되지 않게 한다. */
  private void verifySessionHasNotExpired(StompHeaderAccessor accessor) {
    Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
    if (sessionAttributes == null) {
      throw authenticationFailure(ErrorCode.UNAUTHENTICATED);
    }

    Object value = sessionAttributes.get(SESSION_EXPIRES_AT);
    if (!(value instanceof Instant expiresAt)) {
      throw authenticationFailure(ErrorCode.UNAUTHENTICATED);
    }
    if (sessionManager.isExpired(expiresAt, Instant.now())) {
      throw authenticationFailure(ErrorCode.TOKEN_EXPIRED);
    }
  }

  /** 호출부가 길어지지 않게 공통 코드를 가진 인증 예외를 만든다. */
  private static ChatStompAuthenticationException authenticationFailure(ErrorCode errorCode) {
    return new ChatStompAuthenticationException(errorCode);
  }
}
