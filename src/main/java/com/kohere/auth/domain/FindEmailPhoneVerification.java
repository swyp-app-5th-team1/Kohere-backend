package com.kohere.auth.domain;

import java.time.Instant;
import lombok.Builder;
import lombok.Getter;

/**
 * 임대인 웹 <b>이메일 찾기</b> 전 연락처(휴대폰) 소유 확인 인증번호 챌린지(단명 — Redis 백킹). 가입용 {@link
 * SignupPhoneVerification}과 정책(6자리·코드 TTL 5분·검증 마커 30분·시도 상한 5회)도 식별자 형태(정규화한 번호)도 같지만 <b>사는 키스페이스가
 * 다르다</b>({@code find-email:*} vs {@code signup-phone:*} — 스펙 §1-5 · 시퀀스 US-1-16).
 *
 * <p><b>왜 {@link SignupPhoneVerification}을 그대로 쓰지 않는가</b> — 타입을 공유하면 포트·어댑터도 공유하게 되고, 그 순간 두 흐름이
 * <b>같은 Redis 키</b>를 읽고 쓴다. 마커에는 용도 필드가 없어(가입용 §1-2가 용도 하나뿐이던 시절에 만들어졌다) 이메일 찾기용으로 받은 인증 하나가 회원가입
 * 게이트까지 열어 주고, 반대로 가입하려고 인증한 사람이 그 번호에 붙은 <b>남의 계정 이메일</b>을 덤으로 조회할 수 있다(중고 번호·가족 명의처럼 번호와 명의자가
 * 어긋나는 경우가 실제로 있다). 필드를 뒤늦게 더하는 대신 <b>키를 나눈다</b> — 필드는 읽는 쪽이 검사하는 것을 잊을 수 있지만, 키가 다르면 조회 자체가 실패한다.
 *
 * <p>인증번호는 단방향 해시로만 보관한다(원문 미보관). 대상 번호를 값 필드로 들지 않는 것도 {@link SignupPhoneVerification}과 같은 이유다 —
 * 번호가 곧 키라 값에 또 넣으면 같은 사실이 두 곳에 남아 어긋날 여지만 생긴다.
 */
@Getter
@Builder(toBuilder = true)
public class FindEmailPhoneVerification {

  /** 정규화(숫자만)된 휴대폰 번호 — 이 애그리거트의 식별자이자 Redis 키다. */
  private final String phoneNumber;

  private final String codeHash;
  private final int attempts;
  private final Instant issuedAt;
  private final Instant expiresAt;

  /** 새 인증 시도 발급(attempts=0). {@code phoneNumber}는 이미 정규화된 값이어야 한다. */
  public static FindEmailPhoneVerification issue(
      String phoneNumber, String codeHash, Instant now, long ttlSeconds) {
    return FindEmailPhoneVerification.builder()
        .phoneNumber(phoneNumber)
        .codeHash(codeHash)
        .attempts(0)
        .issuedAt(now)
        .expiresAt(now.plusSeconds(ttlSeconds))
        .build();
  }

  public boolean isExpired(Instant now) {
    return !expiresAt.isAfter(now);
  }

  /** 입력 인증번호 해시가 일치하는지. 대상 번호는 대조하지 않는다 — 챌린지를 번호 키로 찾아온 시점에 이미 같은 번호임이 보장된다. */
  public boolean matches(String candidateCodeHash) {
    return codeHash.equals(candidateCodeHash);
  }

  /** 검증 실패 1회 누적(상한 판정용). */
  public FindEmailPhoneVerification incrementAttempt() {
    return toBuilder().attempts(attempts + 1).build();
  }
}
