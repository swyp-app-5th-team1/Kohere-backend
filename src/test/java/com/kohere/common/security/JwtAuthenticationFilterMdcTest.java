package com.kohere.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.kohere.common.logging.LogFields;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.FilterChain;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * {@link JwtAuthenticationFilter}가 MDC {@code userId}를 채우는 경계 테스트(ADR-0038 용도 3).
 *
 * <p>핵심은 <b>만료는 채우고 위조는 채우지 않는다</b>는 비대칭이다. 만료는 서명 검증을 통과한 뒤 던져져 {@code sub}를 신뢰할 수 있지만, 위조는 서명이
 * 실패해 {@code sub}가 검증되지 않은 공격자 입력이다 — 채우면 감사 로그의 신원 자체가 위조 가능해진다.
 */
class JwtAuthenticationFilterMdcTest {

  private static final String SECRET = "test-secret-test-secret-test-secret-32bytes-min!!";
  private static final String ISSUER = "kohere";

  private JwtAuthenticationFilter filter;
  private SecretKey key;

  @BeforeEach
  void setUp() {
    JwtProperties properties = new JwtProperties();
    properties.setSecret(SECRET);
    properties.setIssuer(ISSUER);
    properties.setAccessTtlSeconds(3600);
    properties.setOnboardingTtlSeconds(1800);
    filter = new JwtAuthenticationFilter(new JwtTokenService(properties));
    key = new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    // 운영에서는 MdcLoggingFilter가 앞서 깔아둔다.
    MDC.put(LogFields.USER_ID, LogFields.ANONYMOUS);
  }

  @AfterEach
  void tearDown() {
    MDC.clear();
    org.springframework.security.core.context.SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("만료 토큰으로 보호 경로 요청 — 401 거부를 실제 userId에 귀속시킨다")
  void expiredToken_attributesDenialToRealUser() throws Exception {
    doFilter("/api/v1/quizzes/random", bearer(signedToken("7", Instant.now().minusSeconds(60))));

    assertThat(MDC.get(LogFields.USER_ID)).isEqualTo("7");
  }

  @Test
  @DisplayName("위조 토큰 — sub가 검증되지 않았으므로 anonymous를 유지한다")
  void forgedToken_neverFillsUserId() throws Exception {
    SecretKey attackerKey =
        new SecretKeySpec(
            "attacker-secret-attacker-secret-32bytes-min!!!!!!".getBytes(StandardCharsets.UTF_8),
            "HmacSHA256");
    String forged =
        Jwts.builder()
            .issuer(ISSUER)
            .subject("42")
            .expiration(Date.from(Instant.now().plusSeconds(3600)))
            .signWith(attackerKey)
            .compact();

    doFilter("/api/v1/quizzes/random", bearer(forged));

    assertThat(MDC.get(LogFields.USER_ID)).isEqualTo(LogFields.ANONYMOUS);
  }

  @Test
  @DisplayName("형식이 깨진 토큰 — anonymous를 유지한다")
  void malformedToken_neverFillsUserId() throws Exception {
    doFilter("/api/v1/quizzes/random", bearer("not-a-jwt"));

    assertThat(MDC.get(LogFields.USER_ID)).isEqualTo(LogFields.ANONYMOUS);
  }

  @Test
  @DisplayName("만료 토큰의 sub가 비숫자면 채우지 않는다 — 로그 인젝션 심층 방어")
  void expiredToken_withNonNumericSubject_isRejected() throws Exception {
    String injected = "7\n2026-07-29 00:00:00 INFO fake line";

    doFilter(
        "/api/v1/quizzes/random", bearer(signedToken(injected, Instant.now().minusSeconds(60))));

    assertThat(MDC.get(LogFields.USER_ID)).isEqualTo(LogFields.ANONYMOUS);
  }

  @Test
  @DisplayName("공개 티어(reissue)의 만료 토큰은 통과하며 익명 취급이라 채우지 않는다")
  void expiredTokenOnPublicPath_staysAnonymous() throws Exception {
    doFilter("/api/v1/auth/reissue", bearer(signedToken("7", Instant.now().minusSeconds(60))));

    assertThat(MDC.get(LogFields.USER_ID)).isEqualTo(LogFields.ANONYMOUS);
  }

  @Test
  @DisplayName("유효한 토큰 — 인증 성공 분기가 실제 userId를 채운다")
  void validToken_fillsUserId() throws Exception {
    doFilter("/api/v1/users/me", bearer(signedToken("7", Instant.now().plusSeconds(3600))));

    assertThat(MDC.get(LogFields.USER_ID)).isEqualTo("7");
    assertThat(MDC.get(LogFields.ONBOARDING)).isEqualTo("true");
  }

  @Test
  @DisplayName("Authorization 헤더가 없으면 필터는 아무것도 하지 않는다")
  void noHeader_staysAnonymous() throws Exception {
    doFilter("/api/v1/quizzes/random", null);

    assertThat(MDC.get(LogFields.USER_ID)).isEqualTo(LogFields.ANONYMOUS);
  }

  @Test
  @DisplayName("WebSocket handshake의 HTTP Bearer는 무시하고 STOMP CONNECT 인증과 섞지 않는다")
  void chatWebSocketHandshake_ignoresHttpBearer() throws Exception {
    doFilter("/ws/chat", bearer(signedToken("7", Instant.now().plusSeconds(3600))));

    // handshake HTTP header는 인증 정본이 아니므로 유효한 token이어도 사용자 신원을 만들지 않는다.
    assertThat(MDC.get(LogFields.USER_ID)).isEqualTo(LogFields.ANONYMOUS);
    assertThat(
            org.springframework.security.core.context.SecurityContextHolder.getContext()
                .getAuthentication())
        .isNull();
  }

  private void doFilter(String path, String authorizationHeader) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
    request.setRequestURI(path);
    if (authorizationHeader != null) {
      request.addHeader(HttpHeaders.AUTHORIZATION, authorizationHeader);
    }
    filter.doFilter(request, new MockHttpServletResponse(), Mockito.mock(FilterChain.class));
  }

  private static String bearer(String token) {
    return "Bearer " + token;
  }

  /** 우리 키로 서명한 토큰. {@code expiration}을 과거로 주면 만료 토큰이 된다. */
  private String signedToken(String subject, Instant expiration) {
    return Jwts.builder()
        .issuer(ISSUER)
        .subject(subject)
        .claim("onboardingCompleted", true)
        .issuedAt(Date.from(Instant.now().minusSeconds(7200)))
        .expiration(Date.from(expiration))
        .signWith(key)
        .compact();
  }
}
