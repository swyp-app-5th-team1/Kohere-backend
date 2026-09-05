package com.kohere.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 사용자별 알림 설정 도메인 값의 식별자 불변식과 허용값 보존을 검증한다. */
class NotificationPreferenceTest {

  /** 생성자가 전달받은 채팅 푸시 허용값을 그대로 보존하는지 확인한다. */
  @Test
  void keepsExplicitChatPushSetting() {
    NotificationPreference preference = new NotificationPreference(10L, false);

    assertThat(preference.userId()).isEqualTo(10L);
    assertThat(preference.chatPushEnabled()).isFalse();
  }

  /** 사용자 ID는 실제 users 식별자처럼 양수여야 한다. */
  @Test
  void rejectsNonPositiveUserId() {
    assertThatThrownBy(() -> new NotificationPreference(0L, true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("userId");
  }
}
