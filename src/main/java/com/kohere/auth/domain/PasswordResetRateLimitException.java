package com.kohere.auth.domain;

import com.kohere.common.exception.BusinessException;
import com.kohere.common.exception.ErrorCode;

/**
 * 비밀번호 재설정 링크 발송 한도 초과(이메일 5회/시간 또는 IP 20회/시간). 전역 핸들러가 429 {@code TOO_MANY_REQUESTS}로 변환한다.
 *
 * <p><b>같은 에러 코드를 쓰는데도 타입을 따로 두는 이유</b> — 응답은 {@link EmailRateLimitException}·{@link
 * PhoneRateLimitException}·{@link LoginRateLimitException}과 구분되지 않아야 하지만(어느 축·어느 기능에 걸렸는지 알려 주면 한도를
 * 역산할 수 있다), <b>로그에 찍히는 예외 타입은 달라야</b> 한다. 재사용하면 "재설정 링크 요청이 막혔다"와 "이메일 인증번호 재발송이 막혔다"가 같은 이름으로 남아,
 * 429가 늘었을 때 어느 경로가 원인인지 추적이 어긋난다. {@link LoginRateLimitException}이 같은 판단을 이미 내려 두었다.
 *
 * <p><b>어느 축에 걸렸는지도 구분해 알리지 않는다</b> — 이메일 축과 IP 축을 가르면 "이 이메일은 오늘 이미 요청됐다"가 새어 나가는데, 그건 §1-8이 응답을
 * 통일해 막으려던 계정 존재 노출과 같은 종류의 누출이다.
 *
 * <p>docs/api/specs/01-auth-onboarding.md §1-8.
 */
public class PasswordResetRateLimitException extends BusinessException {

  public PasswordResetRateLimitException() {
    super(ErrorCode.TOO_MANY_REQUESTS);
  }
}
