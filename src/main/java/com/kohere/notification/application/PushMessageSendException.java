package com.kohere.notification.application;

/** FCM이 토큰별 결과조차 반환하지 못한 전체 발송 실패를 나타낸다. */
public class PushMessageSendException extends RuntimeException {

  /** provider 원인을 보존하되 토큰 원문은 예외 메시지에 포함하지 않는다. */
  public PushMessageSendException(String message, Throwable cause) {
    super(message, cause);
  }
}
