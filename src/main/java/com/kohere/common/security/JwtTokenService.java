package com.kohere.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/**
 * 서버 JWT(access/온보딩 임시 토큰) 발급·검증. 서명은 HS256 대칭키(ADR-0009), 시크릿·만료는 {@link JwtProperties}(ADR-0011).
 *
 * <p>검증 메커니즘은 공유 커널(common)에 두고, 발급은 auth가 이 서비스를 호출한다. 시크릿은 런타임 주입이라 서명 능력은 시크릿을 받은 경계 안에만
 * 있다(ADR-0009 D3). access 토큰은 {@code onboardingCompleted=true}, 신규 회원의 온보딩 임시 토큰은 {@code false}
 * 클레임을 담는다.
 */
@Service
public class JwtTokenService {

  static final String CLAIM_ONBOARDING_COMPLETED = "onboardingCompleted";

  private final SecretKey key;
  private final String issuer;
  private final long accessTtlSeconds;
  private final long onboardingTtlSeconds;

  /**
   * 런타임 설정으로 JWT 서명 키와 발급 정책을 준비한다.
   *
   * @param properties 환경별로 주입되는 서명 비밀키·발급자·토큰 유효기간
   */
  public JwtTokenService(JwtProperties properties) {
    // 문자열 시크릿을 JJWT가 요구하는 HMAC 키로 한 번만 변환해 발급과 검증에 동일하게 사용한다.
    this.key =
        io.jsonwebtoken.security.Keys.hmacShaKeyFor(
            properties.getSecret().getBytes(StandardCharsets.UTF_8));
    this.issuer = properties.getIssuer();
    this.accessTtlSeconds = properties.getAccessTtlSeconds();
    this.onboardingTtlSeconds = properties.getOnboardingTtlSeconds();
  }

  /** 정식 access 토큰(온보딩 완료). 만료 {@link #accessTtlSeconds()}. */
  public String issueAccessToken(long userId) {
    return issue(userId, true, accessTtlSeconds);
  }

  /** 신규 회원 온보딩 전용 임시 토큰(refresh 미발급). 만료 {@link #onboardingTtlSeconds()}. */
  public String issueOnboardingToken(long userId) {
    return issue(userId, false, onboardingTtlSeconds);
  }

  /** 정식 access 토큰의 설정된 유효기간(초)을 반환한다. */
  public long accessTtlSeconds() {
    return accessTtlSeconds;
  }

  /** 온보딩 임시 토큰의 설정된 유효기간(초)을 반환한다. */
  public long onboardingTtlSeconds() {
    return onboardingTtlSeconds;
  }

  /**
   * 서명·만료를 검증하고 주체를 추출한다.
   *
   * @throws io.jsonwebtoken.ExpiredJwtException 만료된 토큰
   * @throws io.jsonwebtoken.JwtException 서명 위조 등 검증 실패
   */
  public AuthPrincipal parse(String token) {
    // 기존 REST 필터와 auth 호출부의 반환 타입을 깨지 않도록 새 검증 결과에서 주체만 꺼낸다.
    return verify(token).principal();
  }

  /**
   * JWT의 서명·발급자·만료를 검증하고, 인증 주체와 검증된 만료 시각을 함께 반환한다.
   *
   * <p>STOMP 연결은 HTTP 요청보다 오래 유지될 수 있으므로 연결 시점에 인증만 성공했다고 끝낼 수 없다. 이후 프레임 처리 시 토큰 만료를 판단할 수 있도록
   * {@code exp}를 반환한다. 서버가 발급하는 토큰에는 항상 {@code exp}가 있으므로, 만료 시각이 없는 토큰은 서명이 유효하더라도 서버 토큰 계약 위반으로
   * 거부한다.
   *
   * @param token {@code Bearer } 접두사를 제거한 JWT 문자열
   * @return 검증된 인증 주체와 만료 시각
   * @throws io.jsonwebtoken.ExpiredJwtException 만료된 토큰
   * @throws io.jsonwebtoken.JwtException 서명 위조·발급자 불일치·필수 만료 시각 누락 등 검증 실패
   */
  public JwtVerificationResult verify(String token) {
    // parseSignedClaims가 서명과 exp를 검증하므로, 검증 전 payload를 신뢰하거나 직접 디코딩하지 않는다.
    Jws<Claims> jws =
        Jwts.parser().verifyWith(key).requireIssuer(issuer).build().parseSignedClaims(token);
    Claims claims = jws.getPayload();

    // 사용자 번호는 서버 발급 시 문자열 subject로 넣는다. 누락·비숫자 값을 NumberFormatException으로 새게 두면
    // REST 필터와 후속 STOMP interceptor의 JWT 오류 처리가 달라지므로 JwtException 계열로 정규화한다.
    long userId = parseUserId(claims.getSubject());
    boolean onboardingCompleted =
        Boolean.TRUE.equals(claims.get(CLAIM_ONBOARDING_COMPLETED, Boolean.class));

    // STOMP 세션의 후속 만료 판단에 쓰일 값이므로 exp가 없는 외부 토큰을 정상 결과로 반환하지 않는다.
    Date expiration = claims.getExpiration();
    if (expiration == null) {
      throw new MalformedJwtException("JWT expiration claim is required");
    }

    AuthPrincipal principal = new AuthPrincipal(userId, onboardingCompleted);
    return new JwtVerificationResult(principal, expiration.toInstant());
  }

  /** 서버 JWT의 subject를 사용자 번호로 바꾸고 잘못된 값은 일관된 JWT 형식 오류로 변환한다. */
  private static long parseUserId(String subject) {
    if (subject == null || subject.isBlank()) {
      throw new MalformedJwtException("JWT subject claim is required");
    }
    try {
      return Long.parseLong(subject);
    } catch (NumberFormatException e) {
      throw new MalformedJwtException("JWT subject must be a numeric user ID", e);
    }
  }

  /** 사용자 상태에 맞는 공통 클레임을 구성하고 HMAC 서명된 JWT 문자열을 만든다. */
  private String issue(long userId, boolean onboardingCompleted, long ttlSeconds) {
    // 발급 시각을 한 번만 구해 iat와 exp가 정확히 ttlSeconds만큼 떨어지게 한다.
    Instant now = Instant.now();
    return Jwts.builder()
        .issuer(issuer)
        .subject(String.valueOf(userId))
        .claim(CLAIM_ONBOARDING_COMPLETED, onboardingCompleted)
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plusSeconds(ttlSeconds)))
        .signWith(key)
        .compact();
  }
}
