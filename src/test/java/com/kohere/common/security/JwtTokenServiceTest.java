package com.kohere.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link JwtTokenService} 단위 테스트.
 *
 * <p>기존 {@link JwtTokenService#parse(String)} 호환성, access/온보딩 권한, 새 검증 결과의 만료 시각, 필수 {@code exp},
 * 위조 토큰 거부를 외부 저장소 없이 검증한다(ADR-0009/0011).
 */
class JwtTokenServiceTest {

  private static final String SECRET = "test-secret-test-secret-test-secret-32bytes-min!!";
  private static final String ISSUER = "kohere";

  private JwtTokenService jwtTokenService;
  private SecretKey signingKey;

  /** 각 테스트가 같은 발급 정책을 사용하도록 서비스와 검증용 키를 새로 준비한다. */
  @BeforeEach
  void setUp() {
    JwtProperties properties = new JwtProperties();
    properties.setSecret(SECRET);
    properties.setIssuer(ISSUER);
    properties.setAccessTtlSeconds(3600);
    properties.setOnboardingTtlSeconds(1800);
    jwtTokenService = new JwtTokenService(properties);
    // 필수 클레임 누락 토큰도 "올바른 서버 서명"으로 만들어야 서명 오류와 계약 오류를 구분해 검증할 수 있다.
    signingKey = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  @DisplayName("기존 parse는 access token의 사용자와 ROLE_USER 근거를 그대로 반환한다")
  void accessToken_carriesOnboardingCompletedTrue() {
    String token = jwtTokenService.issueAccessToken(42L);

    AuthPrincipal principal = jwtTokenService.parse(token);

    assertThat(principal.userId()).isEqualTo(42L);
    assertThat(principal.onboardingCompleted()).isTrue();
  }

  @Test
  @DisplayName("기존 parse는 온보딩 token을 ROLE_ONBOARDING 근거로 구분한다")
  void onboardingToken_carriesOnboardingCompletedFalse() {
    String token = jwtTokenService.issueOnboardingToken(7L);

    AuthPrincipal principal = jwtTokenService.parse(token);

    assertThat(principal.userId()).isEqualTo(7L);
    assertThat(principal.onboardingCompleted()).isFalse();
  }

  @Test
  @DisplayName("verify는 인증 주체와 서명·만료 검증을 통과한 exp 시각을 함께 반환한다")
  void verify_returnsPrincipalAndVerifiedExpiration() {
    Instant beforeIssue = Instant.now();
    String token = jwtTokenService.issueAccessToken(42L);

    JwtVerificationResult result = jwtTokenService.verify(token);

    assertThat(result.principal()).isEqualTo(new AuthPrincipal(42L, true));
    // JWT 날짜는 초 단위로 직렬화되므로 발급 전후 경계를 1초 여유 있게 잡는다.
    assertThat(result.expiresAt())
        .isBetween(beforeIssue.plusSeconds(3599), Instant.now().plusSeconds(3601));
  }

  @Test
  @DisplayName("서명은 유효해도 exp가 없으면 서버 JWT 계약 위반으로 거부한다")
  void verify_rejectsTokenWithoutExpiration() {
    String tokenWithoutExpiration =
        Jwts.builder()
            .issuer(ISSUER)
            .subject("42")
            .claim(JwtTokenService.CLAIM_ONBOARDING_COMPLETED, true)
            .signWith(signingKey)
            .compact();

    assertThatThrownBy(() -> jwtTokenService.verify(tokenWithoutExpiration))
        .isInstanceOf(JwtException.class)
        .hasMessageContaining("expiration");
  }

  @Test
  @DisplayName("서명은 유효해도 sub가 없거나 사용자 번호 형식이 아니면 같은 JWT 형식 오류로 거부한다")
  void verify_rejectsMissingOrNonNumericSubject() {
    Instant expiration = Instant.now().plusSeconds(3600);
    String missingSubject =
        Jwts.builder()
            .issuer(ISSUER)
            .claim(JwtTokenService.CLAIM_ONBOARDING_COMPLETED, true)
            .expiration(java.util.Date.from(expiration))
            .signWith(signingKey)
            .compact();
    String nonNumericSubject =
        Jwts.builder()
            .issuer(ISSUER)
            .subject("not-a-user-id")
            .claim(JwtTokenService.CLAIM_ONBOARDING_COMPLETED, true)
            .expiration(java.util.Date.from(expiration))
            .signWith(signingKey)
            .compact();

    assertThatThrownBy(() -> jwtTokenService.verify(missingSubject))
        .isInstanceOf(JwtException.class)
        .hasMessageContaining("subject");
    assertThatThrownBy(() -> jwtTokenService.verify(nonNumericSubject))
        .isInstanceOf(JwtException.class)
        .hasMessageContaining("numeric");
  }

  @Test
  @DisplayName("다른 키로 서명한 위조 토큰은 검증 결과를 만들지 않는다")
  void parse_rejectsForgedToken() {
    SecretKey attackerKey =
        Keys.hmacShaKeyFor(
            "attacker-secret-attacker-secret-32bytes-min!!!!!!".getBytes(StandardCharsets.UTF_8));
    String forgedToken =
        Jwts.builder()
            .issuer(ISSUER)
            .subject("42")
            .claim(JwtTokenService.CLAIM_ONBOARDING_COMPLETED, true)
            .expiration(java.util.Date.from(Instant.now().plusSeconds(3600)))
            .signWith(attackerKey)
            .compact();

    assertThatThrownBy(() -> jwtTokenService.parse(forgedToken)).isInstanceOf(JwtException.class);
  }

  @Test
  @DisplayName("외부 호출자가 토큰 정책을 확인할 수 있도록 설정 TTL을 노출한다")
  void exposesConfiguredTtls() {
    assertThat(jwtTokenService.accessTtlSeconds()).isEqualTo(3600);
    assertThat(jwtTokenService.onboardingTtlSeconds()).isEqualTo(1800);
  }
}
