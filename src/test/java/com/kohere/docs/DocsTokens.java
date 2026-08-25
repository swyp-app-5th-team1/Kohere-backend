package com.kohere.docs;

import com.kohere.common.security.JwtProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;

/** 문서 테스트가 공유하는 토큰 헬퍼(#151). 7개 DocsTest에 바이트 단위로 동일한 구현이 복제돼 있던 것을 이관했다. */
public final class DocsTokens {

  private DocsTokens() {}

  public static String bearer(String token) {
    return "Bearer " + token;
  }

  /**
   * 구조는 정상 JWT지만 서버와 다른 키로 서명한 문서 테스트용 access token이다.
   *
   * <p>401 예시에도 Bearer 헤더를 남겨 restdocs-api-spec이 스니펫 병합 순서와 무관하게 Swagger 보안 스킴을 생성하게 한다.
   */
  public static String forgedAccessToken() {
    Instant now = Instant.now();
    return Jwts.builder()
        .issuer("kohere")
        .subject("1")
        .claim("onboardingCompleted", true)
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plusSeconds(3600)))
        .signWith(
            Keys.hmacShaKeyFor(
                "forged-doc-only-wrong-secret-please-override-32bytes-min!!"
                    .getBytes(StandardCharsets.UTF_8)))
        .compact();
  }

  /**
   * 만료된 access 토큰. 서명은 유효하고 {@code exp}만 과거다 — 위조 토큰과 달리 게스트로 강등되지 않고 401 {@code TOKEN_EXPIRED}로
   * 재발급을 유도한다.
   *
   * <p>Swagger try-out으로는 원리상 재현할 수 없지만(서버 시크릿 서명이 필요하다) 프론트가 재발급 트리거로 쓰는 계약이라 오퍼레이션당 1건씩 문서에
   * 남긴다(8단계 정본 규칙).
   */
  public static String expiredAccessToken(JwtProperties jwtProperties) {
    SecretKey key = Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
    Instant now = Instant.now();
    return Jwts.builder()
        .issuer(jwtProperties.getIssuer())
        .subject("1")
        .claim("onboardingCompleted", true)
        .issuedAt(Date.from(now.minusSeconds(7200)))
        .expiration(Date.from(now.minusSeconds(3600)))
        .signWith(key)
        .compact();
  }
}
