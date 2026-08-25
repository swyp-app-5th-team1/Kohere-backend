package com.kohere.auth.infrastructure;

import com.kohere.auth.application.AuthProperties;
import com.kohere.auth.domain.LoginAttemptRateLimiter;
import com.kohere.auth.domain.LoginRateLimitException;
import java.time.Duration;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 웹 로그인 시도 레이트리밋 어댑터(Redis). 도메인 포트 {@link LoginAttemptRateLimiter}를 구현한다 — 키 {@code
 * web-login:rate:ip:{IP}}·{@code web-login:rate:email:{소문자 이메일}}에 {@code INCR}로 시도를 세고 첫 증가에서만
 * {@code EXPIRE} 1시간을 건다. 키 짜임새·창 전략은 {@link RedisSignupSmsRateLimiter}와 같은 관용구다(같은 방어를 두 가지 모양으로
 * 만들지 않는다).
 *
 * <p><b>고정 창(fixed window)이다</b> — 창 경계에서 최대 2배가 통과할 수 있지만, 목적이 무차별 대입·해시 비용을 늦추는 것이라 그 오차는 수용한다. 매
 * 증가마다 TTL을 다시 걸면(슬라이딩) 한도에 걸린 사용자가 재시도를 반복하는 동안 창이 끝없이 밀려 <b>영구 차단</b>이 되므로 쓰지 않는다 — 로그인은 정상 사용자도
 * 자주 틀리는 경로라 이 차이가 특히 크다.
 *
 * <p><b>이메일 키는 소문자로 접는다.</b> MySQL의 기본 콜레이션은 대소문자를 구분하지 않아 {@code Kim@x.com}과 {@code kim@x.com}이
 * <b>같은 행</b>을 찾는다. 키를 원문 그대로 쓰면 대소문자만 바꿔가며 같은 계정을 두들겨 이메일 한도를 그냥 지나칠 수 있다.
 *
 * <p><b>다만 두 단위가 완전히 같아지지는 않는다.</b> {@code local_accounts}는 {@code utf8mb4} 기본 콜레이션({@code
 * utf8mb4_0900_ai_ci})이라 대소문자뿐 아니라 <b>악센트도 구분하지 않아</b> {@code usér@x.com}이 {@code user@x.com} 행을
 * 찾는데, 소문자 접기만으로는 그 둘이 다른 버킷에 떨어진다. 그래서 이메일 한도는 <b>정확한 상한이 아니라 근사</b>다 — 실질 방어는 실패 잠금이 먼저 걸어 닫는다(잠긴
 * 계정은 해시 대조 전에 423으로 끊긴다). 정확한 상한이 필요해지면 키를 DB 콜레이션과 같은 방식(NFD 정규화 후 결합 문자 제거)으로 접어야 한다.
 *
 * <p>카운터는 TTL로 사라지므로 키에 실린 이메일·IP도 1시간을 넘겨 남지 않는다. 비밀번호는 어디에도 닿지 않는다.
 */
@Component
@RequiredArgsConstructor
public class RedisLoginAttemptRateLimiter implements LoginAttemptRateLimiter {

  private static final String IP_PREFIX = "web-login:rate:ip:";
  private static final String EMAIL_PREFIX = "web-login:rate:email:";
  private static final Duration WINDOW = Duration.ofHours(1);

  private final StringRedisTemplate redis;
  private final AuthProperties authProperties;

  /**
   * IP·이메일 카운터를 <b>둘 다</b> 올린 뒤 판정한다. 한쪽이 한도를 넘었다고 다른 쪽 증가를 건너뛰면, 한 IP가 이메일을 바꿔가며 태우는 남용이 각 이메일의 한도
   * 뒤에 숨어 IP 카운터에 잡히지 않는다({@link RedisSignupSmsRateLimiter}와 같은 이유).
   */
  @Override
  public void recordAttempt(String email, String clientIp) {
    AuthProperties.Web.Login limits = authProperties.getWeb().getLogin();
    // IP를 판별할 수 없으면(헤더·remote address 모두 부재) 이메일 한도만 적용한다 — 정체 불명 호출을 한 버킷에
    // 몰아넣으면 그 하나가 다른 모든 익명 호출자를 막는다.
    boolean ipExceeded =
        StringUtils.hasText(clientIp)
            && incrementAndCheckExceeded(IP_PREFIX + clientIp, limits.getIpMaxPerHour());
    boolean emailExceeded =
        StringUtils.hasText(email)
            && incrementAndCheckExceeded(
                EMAIL_PREFIX + email.toLowerCase(Locale.ROOT), limits.getEmailMaxPerHour());
    if (ipExceeded || emailExceeded) {
      throw new LoginRateLimitException();
    }
  }

  /** 카운터 1 증가 후 한도 초과 여부. 창의 시작은 첫 증가 시점이다. */
  private boolean incrementAndCheckExceeded(String key, int maxPerHour) {
    Long count = redis.opsForValue().increment(key);
    if (count == null) {
      // 파이프라인·트랜잭션 모드에서만 null이며 이 경로엔 해당이 없다. 카운트를 모르는 채로 429를 내면 정상
      // 사용자를 로그인 자체에서 막게 되므로 통과시킨다(한도는 비용 가드이지 인가가 아니다).
      return false;
    }
    // TTL이 없는 키는 영구 차단이 된다 — 첫 증가에서 걸고, 만에 하나 EXPIRE가 유실됐으면 다시 건다(자가 치유).
    if (count == 1L || redis.getExpire(key) < 0) {
      redis.expire(key, WINDOW);
    }
    return count > maxPerHour;
  }

  /**
   * 이메일 축 카운터 삭제(멱등). 키를 만드는 규칙은 {@link #recordAttempt}와 <b>같은 한 줄</b>이어야 한다 — 한쪽만 {@code
   * toLowerCase}를 빠뜨리면 지운다고 부른 키가 실제로 세고 있던 키와 달라 아무 일도 일어나지 않고, 사용자는 재설정을 마치고도 429를 맞는다.
   */
  @Override
  public void clearEmailCounter(String email) {
    if (!StringUtils.hasText(email)) {
      return;
    }
    redis.delete(EMAIL_PREFIX + email.toLowerCase(Locale.ROOT));
  }
}
