package com.kohere.chat.infrastructure.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.kohere.common.exception.ErrorCode;
import com.kohere.common.security.AuthPrincipal;
import com.kohere.common.security.JwtTokenService;
import com.kohere.common.security.JwtVerificationResult;
import com.kohere.user.api.UserAccountService;
import com.kohere.user.api.UserAccountView;
import io.jsonwebtoken.MalformedJwtException;
import java.time.Instant;
import java.util.HashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

/** STOMP CONNECT 인증의 성공·실패 경계를 broker나 네트워크 없이 빠르게 검증한다. */
@ExtendWith(MockitoExtension.class)
class StompJwtAuthenticationInterceptorTest {

  private static final long USER_ID = 42L;
  private static final String TOKEN = "signed-access-token";
  private static final String SESSION_ID = "session-1";

  @Mock private JwtTokenService jwtTokenService;
  @Mock private UserAccountService userAccountService;
  @Mock private ChatWebSocketSessionManager sessionManager;

  private StompJwtAuthenticationInterceptor interceptor;

  @BeforeEach
  void setUp() {
    interceptor =
        new StompJwtAuthenticationInterceptor(jwtTokenService, userAccountService, sessionManager);
  }

  /** 정상 access token은 ACTIVE 계정까지 확인한 뒤 Principal 이름과 만료시각을 session에 남긴다. */
  @Test
  @DisplayName("CONNECT 성공: userId Principal과 ROLE_USER를 session에 등록한다")
  void authenticatesActiveUser() {
    Instant expiresAt = Instant.now().plusSeconds(3600);
    given(jwtTokenService.verify(TOKEN))
        .willReturn(new JwtVerificationResult(new AuthPrincipal(USER_ID, true), expiresAt));
    given(userAccountService.getAccount(USER_ID))
        .willReturn(new UserAccountView(USER_ID, "ACTIVE", "User", "user@example.com"));

    StompHeaderAccessor accessor = connectAccessor("Bearer " + TOKEN);
    Message<?> message = message(accessor);

    interceptor.preSend(message, ignoredChannel());

    var authentication = (UsernamePasswordAuthenticationToken) accessor.getUser();
    assertThat(authentication).isNotNull();
    assertThat(authentication.getName()).isEqualTo(String.valueOf(USER_ID));
    assertThat(authentication.getAuthorities())
        .extracting(Object::toString)
        .containsExactly("ROLE_USER");
    assertThat(accessor.getSessionAttributes())
        .containsEntry(StompJwtAuthenticationInterceptor.SESSION_USER_ID, USER_ID)
        .containsEntry(StompJwtAuthenticationInterceptor.SESSION_EXPIRES_AT, expiresAt);
    // 검증이 끝난 JWT 원문은 SessionConnectEvent나 후속 로그에서 다시 보이지 않아야 한다.
    assertThat(accessor.getNativeHeader(HttpHeaders.AUTHORIZATION)).isNull();
    verify(sessionManager).scheduleExpiration(SESSION_ID, expiresAt);
  }

  /** header가 없거나 두 개면 어느 token을 정본으로 삼을 수 없으므로 검증 서비스 호출 전에 거부한다. */
  @Test
  @DisplayName("CONNECT 실패: Authorization header가 정확히 하나가 아니면 UNAUTHENTICATED")
  void rejectsMissingOrDuplicateAuthorizationHeader() {
    StompHeaderAccessor missing = connectAccessor(null);
    assertErrorCode(missing, ErrorCode.UNAUTHENTICATED);

    StompHeaderAccessor duplicate = connectAccessor("Bearer first");
    duplicate.addNativeHeader(HttpHeaders.AUTHORIZATION, "Bearer second");
    assertErrorCode(duplicate, ErrorCode.UNAUTHENTICATED);
  }

  /** 서명·issuer·형식 검증이 실패한 JWT는 세부 원인을 노출하지 않고 인증 실패로 통일한다. */
  @Test
  @DisplayName("CONNECT 실패: 잘못된 JWT는 UNAUTHENTICATED")
  void rejectsInvalidToken() {
    given(jwtTokenService.verify(TOKEN)).willThrow(new MalformedJwtException("invalid"));

    StompHeaderAccessor accessor = connectAccessor("Bearer " + TOKEN);
    assertErrorCode(accessor, ErrorCode.UNAUTHENTICATED);
    assertThat(accessor.getNativeHeader(HttpHeaders.AUTHORIZATION)).isNull();
  }

  /** 온보딩 token도 서명은 정상이지만 정식 채팅 사용 권한은 없으므로 별도 코드로 안내한다. */
  @Test
  @DisplayName("CONNECT 실패: 온보딩 미완료 token은 AUTH_ONBOARDING_REQUIRED")
  void rejectsOnboardingToken() {
    given(jwtTokenService.verify(TOKEN))
        .willReturn(
            new JwtVerificationResult(
                new AuthPrincipal(USER_ID, false), Instant.now().plusSeconds(1800)));

    assertErrorCode(connectAccessor("Bearer " + TOKEN), ErrorCode.AUTH_ONBOARDING_REQUIRED);
  }

  /** JWT가 남아 있어도 현재 계정이 ACTIVE가 아니면 채팅 연결을 유지하지 않는다. */
  @Test
  @DisplayName("CONNECT 실패: 비활성 계정은 UNAUTHENTICATED")
  void rejectsInactiveAccount() {
    given(jwtTokenService.verify(TOKEN))
        .willReturn(
            new JwtVerificationResult(
                new AuthPrincipal(USER_ID, true), Instant.now().plusSeconds(3600)));
    given(userAccountService.getAccount(USER_ID))
        .willReturn(new UserAccountView(USER_ID, "WITHDRAWN", null, null));

    assertErrorCode(connectAccessor("Bearer " + TOKEN), ErrorCode.UNAUTHENTICATED);
  }

  /** scheduler close와 거의 동시에 도착한 기능 frame도 만료된 session으로 처리한다. */
  @Test
  @DisplayName("후속 frame 실패: session JWT가 만료됐으면 TOKEN_EXPIRED")
  void rejectsFrameAfterSessionTokenExpiry() {
    StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
    accessor.setSessionId(SESSION_ID);
    accessor.setSessionAttributes(
        new HashMap<>(
            java.util.Map.of(
                StompJwtAuthenticationInterceptor.SESSION_EXPIRES_AT,
                Instant.now().minusSeconds(1))));
    accessor.setLeaveMutable(true);
    given(
            sessionManager.isExpired(
                org.mockito.ArgumentMatchers.any(Instant.class),
                org.mockito.ArgumentMatchers.any(Instant.class)))
        .willReturn(true);

    assertErrorCode(accessor, ErrorCode.TOKEN_EXPIRED);
  }

  /** 수정 가능한 STOMP CONNECT accessor를 만들어 실제 interceptor가 user와 session 값을 기록하게 한다. */
  private static StompHeaderAccessor connectAccessor(String authorization) {
    StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
    accessor.setSessionId(SESSION_ID);
    accessor.setSessionAttributes(new HashMap<>());
    if (authorization != null) {
      accessor.addNativeHeader(HttpHeaders.AUTHORIZATION, authorization);
    }
    accessor.setLeaveMutable(true);
    return accessor;
  }

  private void assertErrorCode(StompHeaderAccessor accessor, ErrorCode expected) {
    assertThatThrownBy(() -> interceptor.preSend(message(accessor), ignoredChannel()))
        .isInstanceOf(ChatStompAuthenticationException.class)
        .satisfies(
            error ->
                assertThat(((ChatStompAuthenticationException) error).getErrorCode())
                    .isEqualTo(expected));
  }

  /** accessor를 원본 그대로 찾을 수 있도록 leaveMutable 상태의 STOMP message를 만든다. */
  private static Message<byte[]> message(StompHeaderAccessor accessor) {
    return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
  }

  /** interceptor 단위 테스트에서는 channel 동작이 필요 없어 최소 mock만 사용한다. */
  private static org.springframework.messaging.MessageChannel ignoredChannel() {
    return org.mockito.Mockito.mock(org.springframework.messaging.MessageChannel.class);
  }
}
