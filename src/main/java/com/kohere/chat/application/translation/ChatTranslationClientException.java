package com.kohere.chat.application.translation;

/**
 * 외부 번역 실패를 안전한 code와 재시도 가능 여부로 바꾼 예외다.
 *
 * <p>provider 응답 본문이나 사용자 원문은 필드와 로그에 담지 않는다.
 */
public class ChatTranslationClientException extends RuntimeException {

  private final String failureCode;
  private final boolean retryable;

  public ChatTranslationClientException(String failureCode, boolean retryable, Throwable cause) {
    super(failureCode, cause);
    this.failureCode = failureCode;
    this.retryable = retryable;
  }

  /** 로그와 DB에 저장할 비민감 오류 분류 code다. */
  public String getFailureCode() {
    return failureCode;
  }

  /** timeout·429·일시적 서버 오류처럼 짧은 재시도가 의미 있는지 알려 준다. */
  public boolean isRetryable() {
    return retryable;
  }
}
