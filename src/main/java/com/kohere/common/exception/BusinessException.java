package com.kohere.common.exception;

import java.util.Map;

/**
 * 모든 비즈니스 예외의 최상위 추상 타입. {@link ErrorCode}를 보유하며, 전역 핸들러가 status·code로 변환한다.
 *
 * <p>각 모듈은 의미가 드러나는 이름의 구체 예외(예: {@code ListingNotFoundException})로 이 타입을 상속한다. 컨트롤러에서 try/catch로
 * 응답을 만들지 않는다. docs/api/error-response-guide.md §5, docs/convention/code-style.md §5.
 */
public abstract class BusinessException extends RuntimeException {

  private final ErrorCode errorCode;

  protected BusinessException(ErrorCode errorCode) {
    super(errorCode.getDefaultMessage());
    this.errorCode = errorCode;
  }

  protected BusinessException(ErrorCode errorCode, String message) {
    super(message);
    this.errorCode = errorCode;
  }

  public ErrorCode getErrorCode() {
    return errorCode;
  }

  /**
   * 응답 {@code error.details}에 실을 코드별 부가 데이터. 기본은 {@code null}이며 <b>필요한 예외만</b> 재정의한다.
   *
   * <p>{@code null}이면 직렬화에서 키 자체가 빠지므로(={@link com.kohere.common.response.ErrorResponse}) 재정의하지 않은
   * 예외의 응답 외형은 종전과 완전히 같다. 여기에 무엇을 담을지는 그 코드의 API 스펙이 정하며, <b>스펙에 적히지 않은 값을 임의로 싣지 않는다</b>(ADR-0004
   * Amended).
   */
  public Map<String, Object> getDetails() {
    return null;
  }
}
