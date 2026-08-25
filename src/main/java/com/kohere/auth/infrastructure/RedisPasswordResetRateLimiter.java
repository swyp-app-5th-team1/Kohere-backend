package com.kohere.auth.infrastructure;

import com.kohere.auth.application.AuthProperties;
import com.kohere.auth.domain.PasswordResetRateLimitException;
import com.kohere.auth.domain.PasswordResetRateLimiter;
import java.time.Duration;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 재설정 링크 발송 레이트리밋 어댑터(Redis). 도메인 포트 {@link PasswordResetRateLimiter}를 구현한다 — 키 {@code
 * pwd-reset:rate:email:{소문자 이메일}}·{@code pwd-reset:rate:ip:{IP}}에 {@code INCR}로 시도를 세고 첫 증가에서만
 * {@code EXPIRE} 1시간을 건다. 키 짜임새·창 전략은 {@link RedisSignupSmsRateLimiter}·{@link
 * RedisLoginAttemptRateLimiter}와 같은 관용구다(같은 방어를 세 가지 모양으로 만들지 않는다).
 *
 * <p><b>{@code web-login:rate:*}를 공유하지 않는 것이 이 클래스에서 가장 중요한 한 줄이다.</b> 키 접두만 갈아 끼우면 코드가 줄어 보이지만,
 * 공유하는 순간 재설정 요청이 로그인 시도 예산을 깎는다 — {@code app.auth.web.login.email-max-per-hour}(20)는 <b>잠금
 * 임계값(10)의 2배</b>로 잡힌 값이고, 그 2배는 "정상 사용자가 몇 번 틀리고 다시 맞히는 흐름을 막지 않으면서 잠금은 도달 가능하게" 두려고 고른 값이다. 예산이
 * 깎이면 <b>잠금이 사실상 도달 불가능</b>해지고(한 시간 창의 이메일 예산을 정확히 전부 실패로 써야 상한에 닿는다), 잠금을 검증하던 기존 테스트는 423이 아니라
 * 429를 받아 깨진다. 반대 방향도 나쁘다 — 로그인을 반복해 틀린 사람이 그 때문에 <b>복구 링크조차 받지 못한다</b>. 두 카운터는 서로를 모르는 채로 살아야 한다.
 *
 * <p><b>고정 창(fixed window)이다</b> — 창 경계에서 최대 2배가 통과할 수 있지만, 목적이 발송비·메일 폭탄 억제라 그 오차는 수용한다. 매 증가마다
 * TTL을 다시 걸면(슬라이딩) 한도에 걸린 사용자가 재시도를 반복하는 동안 창이 끝없이 밀려 <b>영구 차단</b>이 된다.
 *
 * <p><b>이메일 키는 소문자로 접는다.</b> {@code local_accounts}의 콜레이션이 대소문자를 구분하지 않아 {@code Kim@x.com}과 {@code
 * kim@x.com}이 같은 행을 찾으므로, 키를 원문 그대로 쓰면 대소문자만 바꿔가며 같은 메일함에 링크를 쏟아부을 수 있다. 악센트까지는 접지 못해 한도가 정확한 상한이
 * 아니라 근사인 것도 {@link RedisLoginAttemptRateLimiter}와 같다.
 *
 * <p>카운터는 TTL로 사라지므로 키에 실린 이메일·IP도 1시간을 넘겨 남지 않는다.
 */
@Component
@RequiredArgsConstructor
public class RedisPasswordResetRateLimiter implements PasswordResetRateLimiter {

  private static final String EMAIL_PREFIX = "pwd-reset:rate:email:";
  private static final String IP_PREFIX = "pwd-reset:rate:ip:";
  private static final Duration WINDOW = Duration.ofHours(1);

  private final StringRedisTemplate redis;
  private final AuthProperties authProperties;

  /**
   * 이메일·IP 카운터를 <b>둘 다</b> 올린 뒤 판정한다. 한쪽이 한도를 넘었다고 다른 쪽 증가를 건너뛰면, 한 IP가 이메일을 바꿔가며 태우는 남용이 각 이메일의 한도
   * 뒤에 숨어 IP 카운터에 잡히지 않는다.
   *
   * <p>초과는 {@link PasswordResetRateLimitException}(429)이며 어느 축에 걸렸는지 구분해 알리지 않는다. 새 예외 타입을 만들지 않은
   * 것은 이 경로가 <b>이메일 채널의 발송 한도</b>라는 점에서 그 예외의 뜻과 같기 때문이다(코드도 {@code TOO_MANY_REQUESTS} 하나다).
   */
  @Override
  public void recordAttempt(String email, String clientIp) {
    AuthProperties.Web.PasswordReset limits = authProperties.getWeb().getPasswordReset();
    boolean emailExceeded =
        StringUtils.hasText(email)
            && incrementAndCheckExceeded(
                EMAIL_PREFIX + email.toLowerCase(Locale.ROOT), limits.getEmailMaxPerHour());
    // IP를 판별할 수 없으면(헤더·remote address 모두 부재) 이메일 한도만 적용한다 — 정체 불명 호출을 한 버킷에
    // 몰아넣으면 그 하나가 다른 모든 익명 호출자를 막는다.
    boolean ipExceeded =
        StringUtils.hasText(clientIp)
            && incrementAndCheckExceeded(IP_PREFIX + clientIp, limits.getIpMaxPerHour());
    if (emailExceeded || ipExceeded) {
      throw new PasswordResetRateLimitException();
    }
  }

  /** 카운터 1 증가 후 한도 초과 여부. 창의 시작은 첫 증가 시점이다. */
  private boolean incrementAndCheckExceeded(String key, int maxPerHour) {
    Long count = redis.opsForValue().increment(key);
    if (count == null) {
      // 파이프라인·트랜잭션 모드에서만 null이며 이 경로엔 해당이 없다. 카운트를 모르는 채로 429를 내면 정상
      // 사용자를 복구 경로에서 막게 되므로 통과시킨다(한도는 비용 가드이지 인가가 아니다).
      return false;
    }
    // TTL이 없는 키는 영구 차단이 된다 — 첫 증가에서 걸고, 만에 하나 EXPIRE가 유실됐으면 다시 건다(자가 치유).
    if (count == 1L || redis.getExpire(key) < 0) {
      redis.expire(key, WINDOW);
    }
    return count > maxPerHour;
  }
}
