package com.kohere.auth.domain;

import java.time.Duration;
import java.time.Instant;
import lombok.Builder;
import lombok.Getter;

/**
 * 비밀번호 재설정 토큰 애그리거트(US-1-17 · 스펙 §1-8~§1-10). 메일로 나간 링크 하나의 수명을 담는 <b>단명 자격증명</b>이며, 저장소는
 * Redis다({@link PasswordResetTokenRepository}).
 *
 * <p><b>토큰 원문은 이 객체에 없다.</b> 들고 있는 것은 {@code tokenHash}뿐이고, 원문은 생성 직후 메일 본문에 실린 뒤 그대로 버려진다 —
 * 저장소·로그·응답 어디에도 남지 않는다({@link RefreshToken}과 같은 규칙, ADR-0006). 유출된 저장소 덤프 하나로 남의 계정을 재설정할 수 있게 되는
 * 것이 원문 보관의 대가라 값이 없다.
 *
 * <p><b>{@code email}을 함께 들고 있는 이유</b> — 사전 확인(§1-9)은 "이 링크가 어느 계정 것인가"를 마스킹해 돌려주어야 하고, 확정(§1-10)은
 * 마지막에 로그인 시도 카운터를 <b>이메일 축</b>으로 지워야 한다({@link LoginAttemptRateLimiter#clearEmailCounter}). {@code
 * userId}만 들고 있으면 두 경우 모두 MySQL을 한 번 더 다녀와야 하는데, 사전 확인은 <b>토큰만 있으면 부를 수 있는 경로</b>라 그 조회 자체가 익명 호출자가
 * 유발하는 DB 부하가 된다.
 *
 * <p><b>여기 담기는 이메일은 제출값이 아니라 {@code local_accounts}에 저장된 값</b>이다. 웹 자격증명 테이블의 콜레이션은 대소문자·악센트를 구분하지
 * 않아 {@code Kim@x.com}으로 요청해도 {@code kim@x.com} 행이 잡힌다 — 제출값을 그대로 실으면 §1-9의 마스킹 이메일이 화면마다 달라 보이고, 더
 * 나쁘게는 §1-10의 카운터 삭제가 <b>실제로 세고 있던 키와 다른 키</b>를 지워 복구를 마친 사용자가 로그인 화면에서 429를 맞는다.
 *
 * <p>만료 판정을 도메인이 들고 있는 것은 Redis TTL과 <b>이중</b>이지만 의도된 것이다 — TTL은 저장소 구현의 성질이라, 어댑터를 바꾸거나 TTL을 놓친 키가
 * 하나라도 생기면 만료가 사라진다. 유효 기간은 저장소가 아니라 이 토큰의 계약이다.
 *
 * <p>docs/api/specs/01-auth-onboarding.md §1-8·§1-9·§1-10.
 */
@Getter
@Builder
public class PasswordResetToken {

  private final String tokenHash;
  private final Long userId;
  private final String email;
  private final Instant issuedAt;
  private final Instant expiresAt;

  /**
   * 새 재설정 토큰 발급 — 해시와 대상 계정만 받는다.
   *
   * @param tokenHash {@code SHA-256(원문 + pepper)}. 원문을 넘기는 오버로드를 두지 않는다 — 두면 언젠가 그 원문이 저장 경로로 흘러간다
   * @param email {@code local_accounts}에 저장된 값(제출값이 아니다 — 클래스 주석 참조)
   * @param ttlSeconds 링크 유효 시간({@code app.auth.web.password-reset.token-ttl-seconds})
   */
  public static PasswordResetToken issue(
      String tokenHash, Long userId, String email, Instant now, long ttlSeconds) {
    return PasswordResetToken.builder()
        .tokenHash(tokenHash)
        .userId(userId)
        .email(email)
        .issuedAt(now)
        .expiresAt(now.plusSeconds(ttlSeconds))
        .build();
  }

  /** 만료 여부. 경계 시각({@code now == expiresAt})은 만료로 본다 — 링크의 수명은 닫힌 구간이 아니다. */
  public boolean isExpired(Instant now) {
    return !now.isBefore(expiresAt);
  }

  /**
   * 남은 유효 시간(초) — §1-9 응답의 {@code expiresIn}이다. <b>발급 시 고정값이 아니라 호출 시점 기준 잔여</b>라 화면의 카운트다운이 링크를 늦게
   * 연 사용자에게도 사실을 말한다.
   *
   * <p>음수는 0으로 접는다. 만료된 토큰은 호출부가 이미 422로 끊으므로 정상 경로에서는 닿지 않지만, 응답에 음수 초가 실리면 클라이언트 타이머가 거꾸로 돈다.
   */
  public long remainingSeconds(Instant now) {
    return Math.max(0L, Duration.between(now, expiresAt).toSeconds());
  }
}
