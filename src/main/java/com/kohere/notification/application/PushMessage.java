package com.kohere.notification.application;

import java.util.List;
import java.util.Map;

/**
 * 한 사용자에게 보낼 완성된 FCM notification/data payload와 대상 토큰 목록이다.
 *
 * <p>FCM SDK 타입을 애플리케이션 계층에 노출하지 않는다. 토큰과 data 값은 자르거나 정규화하지 않고 불변 복사해 발송 중 변경되지 않게 한다.
 *
 * @param fcmTokens 수신자의 활성 FCM 토큰 목록
 * @param title iOS 시스템 알림 제목
 * @param body iOS 시스템 알림 본문
 * @param data 알림 클릭 뒤 앱이 사용할 문자열 부가 데이터
 */
public record PushMessage(
    List<String> fcmTokens, String title, String body, Map<String, String> data) {

  /** 빈 대상이나 불완전한 표시값이 Firebase 어댑터까지 전달되지 않게 검증하고 컬렉션을 복사한다. */
  public PushMessage {
    if (fcmTokens == null || fcmTokens.isEmpty()) {
      throw new IllegalArgumentException("at least one FCM token is required");
    }
    if (fcmTokens.stream().anyMatch(token -> token == null || token.isBlank())) {
      throw new IllegalArgumentException("FCM tokens must not be blank");
    }
    if (title == null || title.isBlank() || body == null || body.isBlank()) {
      throw new IllegalArgumentException("push title and body are required");
    }
    if (data == null || data.isEmpty()) {
      throw new IllegalArgumentException("push data is required");
    }

    fcmTokens = List.copyOf(fcmTokens);
    data = Map.copyOf(data);
  }
}
