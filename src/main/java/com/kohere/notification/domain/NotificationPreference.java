package com.kohere.notification.domain;

/**
 * 한 사용자의 알림 수신 선택을 표현하는 Notification 도메인 값이다.
 *
 * <p>현재는 계정 전체의 채팅 푸시 허용 여부만 소유한다. 설정 행 자체가 없는 사용자는 repository가 아니라 application service에서 허용으로
 * 해석한다.
 *
 * @param userId 설정 소유 사용자 ID
 * @param chatPushEnabled 모든 등록 기기의 채팅 푸시 허용 여부
 */
public record NotificationPreference(long userId, boolean chatPushEnabled) {

  /** 사용자별 설정이 잘못된 식별자로 생성되지 않도록 도메인 불변식을 검증한다. */
  public NotificationPreference {
    if (userId < 1) {
      throw new IllegalArgumentException("userId must be positive");
    }
  }
}
