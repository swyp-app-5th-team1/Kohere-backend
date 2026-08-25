package com.kohere.auth.infrastructure;

import com.kohere.auth.domain.FindEmailPhoneVerification;
import com.kohere.auth.domain.FindEmailPhoneVerificationRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 이메일 찾기용 연락처 인증 영속 어댑터(Redis). 도메인 포트 {@link FindEmailPhoneVerificationRepository}를 구현한다(가입용
 * {@link SignupPhoneVerificationRedisRepository} 미러, ADR-0006).
 *
 * <p>키 {@code find-email:code:{정규화번호}} = Hash(codeHash·attempts·issuedAt·expiresAt), TTL=만료 시각.
 * {@code find-email:verified:{정규화번호}} = String({@code "1"}), TTL=설정값. 스펙 §1-5의 키 표.
 *
 * <p><b>이 클래스의 존재 이유는 접두사 두 개뿐이다 — 그리고 그 둘이 전부다.</b> 가입용 어댑터와 로직이 같은데도 {@code signup-phone:*}를
 * 재사용하지 않는 이유는 두 가지다.
 *
 * <ul>
 *   <li><b>인가 범위 확대</b> — 검증 마커에는 용도 필드가 없다(§1-2가 용도 하나뿐이던 시절에 만들어졌다). 키를 공유하면 이메일 찾기용으로 받은 인증 하나가
 *       <b>회원가입(§1-3)의 SMS 게이트까지</b> 통과시키고, 반대로 가입용 마커가 §1-7의 이메일 조회를 열어 준다. 값에 용도 필드를 뒤늦게 더하는 대신
 *       키를 나눈다 — 필드는 읽는 쪽이 검사하는 것을 잊을 수 있지만, <b>키가 다르면 조회 자체가 실패</b>한다.
 *   <li><b>챌린지 덮어쓰기</b> — 키가 같으면 한 사용자가 가입 화면과 이메일 찾기 화면을 오갈 때 나중 발송이 앞의 챌린지를 지운다. 사용자는 두 통의 문자를
 *       받았는데 앞의 것으로는 확인되지 않는다.
 * </ul>
 *
 * <p>가입용 어댑터와 마찬가지로 <b>대상 번호를 값에 담지 않는다</b> — 번호가 곧 키라 값에 또 넣으면 같은 사실이 두 곳에 남고, 검증 마커도 존재 자체가 의미라
 * 상수 {@code "1"}만 둔다. 애그리거트의 {@code phoneNumber}는 조회할 때 키에서 되살린다.
 */
@Repository
@RequiredArgsConstructor
public class FindEmailPhoneVerificationRedisRepository
    implements FindEmailPhoneVerificationRepository {

  private static final String CODE_PREFIX = "find-email:code:";
  private static final String VERIFIED_PREFIX = "find-email:verified:";
  private static final String FIELD_CODE_HASH = "codeHash";
  private static final String FIELD_ATTEMPTS = "attempts";
  private static final String FIELD_ISSUED_AT = "issuedAt";
  private static final String FIELD_EXPIRES_AT = "expiresAt";

  /** 검증 완료 마커 값 — 번호는 키에 있으므로 값은 존재 표시뿐이다(스펙 §1-6). */
  private static final String VERIFIED_MARKER = "1";

  private final StringRedisTemplate redis;

  @Override
  public void saveChallenge(FindEmailPhoneVerification challenge) {
    String key = CODE_PREFIX + challenge.getPhoneNumber();
    redis
        .opsForHash()
        .putAll(
            key,
            Map.of(
                FIELD_CODE_HASH, challenge.getCodeHash(),
                FIELD_ATTEMPTS, String.valueOf(challenge.getAttempts()),
                FIELD_ISSUED_AT, String.valueOf(challenge.getIssuedAt().toEpochMilli()),
                FIELD_EXPIRES_AT, String.valueOf(challenge.getExpiresAt().toEpochMilli())));
    // TTL은 항상 최초 발급 시각 기준 만료로 다시 건다 — attempts 누적 저장이 유효기간을 연장하면 안 된다.
    redis.expireAt(key, challenge.getExpiresAt());
  }

  @Override
  public Optional<FindEmailPhoneVerification> findChallenge(String phoneNumber) {
    Map<Object, Object> entries = redis.opsForHash().entries(CODE_PREFIX + phoneNumber);
    if (entries.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        FindEmailPhoneVerification.builder()
            .phoneNumber(phoneNumber)
            .codeHash((String) entries.get(FIELD_CODE_HASH))
            .attempts(Integer.parseInt((String) entries.get(FIELD_ATTEMPTS)))
            .issuedAt(Instant.ofEpochMilli(Long.parseLong((String) entries.get(FIELD_ISSUED_AT))))
            .expiresAt(Instant.ofEpochMilli(Long.parseLong((String) entries.get(FIELD_EXPIRES_AT))))
            .build());
  }

  @Override
  public void deleteChallenge(String phoneNumber) {
    redis.delete(CODE_PREFIX + phoneNumber);
  }

  @Override
  public void markVerified(String phoneNumber, long ttlSeconds) {
    redis
        .opsForValue()
        .set(VERIFIED_PREFIX + phoneNumber, VERIFIED_MARKER, Duration.ofSeconds(ttlSeconds));
  }

  /**
   * 값을 읽지 않고 <b>키 존재만</b> 본다 — 값이 상수 {@code "1"}이라 읽어도 얻을 정보가 없고, 만료는 TTL이 이미 처리한다. {@code hasKey}는
   * {@code Boolean}을 돌려주므로(연결 실패 시 {@code null} 가능) 박싱을 풀지 않고 대조한다.
   */
  @Override
  public boolean isVerified(String phoneNumber) {
    return Boolean.TRUE.equals(redis.hasKey(VERIFIED_PREFIX + phoneNumber));
  }

  @Override
  public void deleteVerified(String phoneNumber) {
    redis.delete(VERIFIED_PREFIX + phoneNumber);
  }
}
