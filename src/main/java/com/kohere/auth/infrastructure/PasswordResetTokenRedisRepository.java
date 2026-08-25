package com.kohere.auth.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kohere.auth.domain.PasswordResetToken;
import com.kohere.auth.domain.PasswordResetTokenRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 비밀번호 재설정 토큰 영속 어댑터(Redis). 도메인 포트 {@link PasswordResetTokenRepository}를 구현한다(ADR-0006의 refresh 패턴
 * 미러).
 *
 * <p>키 {@code pwd-reset:{tokenHash}} = JSON({@code userId}·{@code email}·{@code issuedAt}·{@code
 * expiresAt}), TTL = 토큰의 {@code expiresAt}. 원문 토큰은 키에도 값에도 없다.
 *
 * <p><b>왜 Redis Hash가 아니라 값 하나에 직렬화하는가 — 원자성 때문이다.</b> 이 저장소의 소비({@link #consume})는 <b>읽으면서 지우는 명령
 * 하나</b>여야 한다. Hash로 두면 {@code HGETALL} 다음 {@code DEL}이라 두 명령 사이에 창이 생기고, 같은 링크를 동시에 두 번 누른 두 요청이
 * <b>둘 다 검증을 통과해 일회용이 아니게 된다</b>. 메일 클라이언트의 링크 프리페치·사용자의 더블클릭은 예외 상황이 아니라 흔한 기본 동작이고, 새어 나간 두 번째
 * 통과는 서버 로그에 정상 요청으로만 남아 사후에 찾을 수도 없다. 대안은 Lua 스크립트였지만, <b>값 하나 + {@code GETDEL}</b>이 같은 보장을 스크립트
 * 없이 얻는다 — 저장소에 로직을 두지 않는 쪽이 읽기도 검증하기도 쉽다.
 *
 * <p>구조가 다른 대가로 {@code RefreshTokenRedisRepository}·{@link EmailVerificationRedisRepository}의 Hash
 * 관용구와 모양이 갈리지만, 그 둘은 <b>필드 하나만 갱신</b>하는 동작(회전 시 {@code status} 전이, 시도 횟수 증가)이 있어 Hash가 맞았다. 이 토큰에는
 * 부분 갱신이 없다 — 발급되고, 한 번 읽히고, 통째로 사라진다.
 *
 * <p>키의 TTL이 곧 토큰의 만료라 만료된 토큰을 청소하는 배치가 필요 없고, 키에 실린 해시·값에 실린 이메일도 30분을 넘겨 남지 않는다.
 */
@Repository
@RequiredArgsConstructor
public class PasswordResetTokenRedisRepository implements PasswordResetTokenRepository {

  private static final String KEY_PREFIX = "pwd-reset:";

  private final StringRedisTemplate redis;
  private final ObjectMapper objectMapper;

  /**
   * 발급된 토큰 저장. <b>{@code SET key value EX ttl} 한 명령</b>으로 쓴다 — {@code SET} 다음 {@code EXPIRE}로 나누면 그
   * 사이에 프로세스가 죽었을 때 <b>TTL 없는 재설정 토큰</b>이 영원히 남는다(만료 없는 계정 탈취 수단이다). 도메인이 만료를 함께 들고 있어 그 토큰도 검증에서는
   * 걸리지만, 저장소에 영구히 남을 이유는 없다.
   *
   * <p>이미 지난 만료로 저장을 요청받으면(시계 왜곡·설정 실수) TTL이 0 이하가 되어 Redis가 거부하므로 최소 1초로 접는다 — 어차피 다음 조회에서 도메인이
   * 만료로 끊는다.
   */
  @Override
  public void save(PasswordResetToken token) {
    Duration ttl = Duration.between(Instant.now(), token.getExpiresAt());
    if (ttl.isNegative() || ttl.isZero()) {
      ttl = Duration.ofSeconds(1);
    }
    redis.opsForValue().set(KEY_PREFIX + token.getTokenHash(), serialize(token), ttl);
  }

  /** 읽기 전용 조회(§1-9) — 키를 남긴다. 프리페치로 링크가 미리 열려도 토큰이 죽지 않아야 한다. */
  @Override
  public Optional<PasswordResetToken> find(String tokenHash) {
    return deserialize(tokenHash, redis.opsForValue().get(KEY_PREFIX + tokenHash));
  }

  /**
   * 원자 소비(§1-10) — {@code GETDEL}. 반환된 값이 있다는 것 자체가 "이 요청이 그 토큰을 가져간 유일한 요청"이라는 뜻이고, 동시에 들어온 두 번째
   * 요청은 {@link Optional#empty()}를 받는다.
   *
   * <p>만료 여부는 판정하지 않고 그대로 돌려준다 — "없음"과 "만료"의 구분은 응답에서는 지워지지만(같은 422) 서버 쪽에서까지 지울 이유는 없다.
   */
  @Override
  public Optional<PasswordResetToken> consume(String tokenHash) {
    return deserialize(tokenHash, redis.opsForValue().getAndDelete(KEY_PREFIX + tokenHash));
  }

  private String serialize(PasswordResetToken token) {
    try {
      return objectMapper.writeValueAsString(
          new StoredToken(
              token.getUserId(),
              token.getEmail(),
              token.getIssuedAt().toEpochMilli(),
              token.getExpiresAt().toEpochMilli()));
    } catch (JsonProcessingException e) {
      // 필드가 넷뿐인 record라 정상 경로에서는 나올 수 없다. 링크를 못 보내는 것이 깨진 값을 저장하는 것보다 낫다.
      throw new IllegalStateException("재설정 토큰 직렬화 실패", e);
    }
  }

  /**
   * 저장값 역직렬화. 키가 없으면 빈 값이다.
   *
   * <p>깨진 값(수동 수정·포맷 변경 배포 중 남은 구버전)은 <b>예외가 아니라 빈 값</b>으로 접는다 — 호출부에서 422가 되어 사용자는 링크를 다시 받으면 되지만,
   * 500으로 나가면 복구 화면 전체가 멈춘다.
   */
  private Optional<PasswordResetToken> deserialize(String tokenHash, String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    try {
      StoredToken stored = objectMapper.readValue(raw, StoredToken.class);
      return Optional.of(
          PasswordResetToken.builder()
              .tokenHash(tokenHash)
              .userId(stored.userId())
              .email(stored.email())
              .issuedAt(Instant.ofEpochMilli(stored.issuedAt()))
              .expiresAt(Instant.ofEpochMilli(stored.expiresAt()))
              .build());
    } catch (JsonProcessingException e) {
      return Optional.empty();
    }
  }

  /**
   * 저장 표현. 시각은 {@code Instant} 대신 epoch milli로 담는다 — 기존 Redis 어댑터들과 같은 표기라 {@code redis-cli}로 들여다볼
   * 때 값의 의미가 갈리지 않고, Jackson의 시간 모듈 등록 여부에 좌우되지 않는다.
   */
  private record StoredToken(Long userId, String email, long issuedAt, long expiresAt) {}
}
