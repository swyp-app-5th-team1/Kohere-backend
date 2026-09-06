package com.kohere.notification.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** {@code notification_preferences} 테이블의 컬럼과 JPA 타입 변환만 담당하는 내부 엔티티다. */
@Entity
@Table(name = "notification_preferences")
@Getter
@NoArgsConstructor
@AllArgsConstructor
class NotificationPreferenceJpaEntity {

  /** 사용자 한 명당 설정 행 하나만 허용하는 자연 키다. */
  @Id private Long userId;

  /** 해당 사용자의 모든 앱 설치본에 공통 적용되는 채팅 푸시 허용 여부다. */
  @Column(nullable = false)
  private boolean chatPushEnabled;

  /** 사용자가 알림 설정을 처음 저장한 UTC 시각이다. */
  @Column(nullable = false)
  private Instant createdAt;

  /** 사용자가 알림 설정을 마지막으로 변경한 UTC 시각이다. */
  @Column(nullable = false)
  private Instant updatedAt;
}
