package com.kohere.notification.infrastructure.persistence;

import com.kohere.notification.domain.PushPlatform;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** {@code push_devices} 테이블의 컬럼과 Hibernate 타입 변환만 담당하는 JPA 엔티티다. */
@Entity
@Table(name = "push_devices")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
class PushDeviceJpaEntity {

  /** MySQL IDENTITY가 발급하는 내부 식별자다. */
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** users 모듈과 JPA 관계를 만들지 않고 사용자 ID 값만 보관한다. */
  @Column(nullable = false)
  private Long userId;

  /** 앱 UUID를 36자 문자열이 아닌 MySQL BINARY(16)으로 보관한다. */
  @JdbcTypeCode(SqlTypes.BINARY)
  @Column(nullable = false, columnDefinition = "binary(16)")
  private UUID installationId;

  /** FCM이 발급한 opaque 토큰이며 애플리케이션에서 자르거나 대소문자를 변경하지 않는다. */
  @Column(nullable = false, length = 1024)
  private String fcmToken;

  /** enum 순서가 아닌 이름 IOS를 저장해 DB CHECK 값과 일치시킨다. */
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private PushPlatform platform;

  /** 앱이 현재 토큰을 마지막으로 등록·갱신한 UTC 시각이다. */
  @Column(nullable = false)
  private Instant lastSeenAt;

  /** 최초 등록 UTC 시각이며 갱신에도 유지한다. */
  @Column(nullable = false)
  private Instant createdAt;

  /** 사용자·토큰·마지막 확인 시각의 최종 변경 UTC 시각이다. */
  @Column(nullable = false)
  private Instant updatedAt;
}
