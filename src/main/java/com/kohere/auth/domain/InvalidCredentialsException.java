package com.kohere.auth.domain;

import com.kohere.common.exception.BusinessException;
import com.kohere.common.exception.ErrorCode;
import java.util.Map;

/**
 * 웹 로그인의 이메일 미존재 또는 비밀번호 불일치. 전역 핸들러가 401 {@code AUTH_INVALID_CREDENTIALS}로 변환한다. 두 경우를 한 예외로 묶어
 * 코드와 status, 문구를 같게 만든다(US-1-12).
 *
 * <p><b>{@code details}만 갈린다.</b> 계정이 실재하는데 비밀번호가 틀린 경우에만 누적 실패 횟수와 잠금 상한을 싣는다 — 미등록 이메일에는 올릴 카운터가
 * 없어 실을 값이 없다. 그래서 이 필드의 유무로 가입 여부를 알 수 있으며, 그 <b>계정 열거는 수용한 결과</b>다: 잠금에 해제 경로가 없는 이상 잠기기 전에 남은
 * 시도를 알려 주는 편이 낫고, 잠긴 계정의 423이 이미 계정 존재를 드러내고 있다(ADR-0047 Amended).
 */
public class InvalidCredentialsException extends BusinessException {

  private final Map<String, Object> details;

  /** 미등록 이메일·비ACTIVE 계정 — 실을 카운터가 없어 {@code details}가 나가지 않는다. */
  public InvalidCredentialsException() {
    super(ErrorCode.AUTH_INVALID_CREDENTIALS);
    this.details = null;
  }

  /**
   * 비밀번호 불일치 — 저장된 카운터와 상한을 응답에 싣는다.
   *
   * <p><b>{@code failedAttempts}는 저장에 쓴 그 값이어야 한다</b>(DB를 다시 읽지 않는다). 같은 값이 잠금 판정에도 쓰이므로, 이 숫자가 상한에
   * 닿은 응답이 곧 잠금이 걸린 시점이다 — 클라이언트가 보는 순서가 어긋나지 않는다.
   *
   * @param failedAttempts 이번 실패까지 누적된 연속 실패 횟수
   * @param maxFailedAttempts 계정을 잠그는 상한
   */
  public InvalidCredentialsException(int failedAttempts, int maxFailedAttempts) {
    super(ErrorCode.AUTH_INVALID_CREDENTIALS);
    this.details = Map.of("failedAttempts", failedAttempts, "maxFailedAttempts", maxFailedAttempts);
  }

  @Override
  public Map<String, Object> getDetails() {
    return details;
  }
}
