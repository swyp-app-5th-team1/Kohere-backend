package com.kohere.notification.infrastructure.persistence;

import com.kohere.notification.domain.NotificationPreference;
import com.kohere.notification.domain.NotificationPreferenceRepository;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/** 도메인 {@link NotificationPreferenceRepository}를 Spring Data JPA와 MySQL 저장으로 연결하는 어댑터다. */
@Repository
@RequiredArgsConstructor
public class NotificationPreferenceRepositoryImpl implements NotificationPreferenceRepository {

  private final NotificationPreferenceJpaRepository jpaRepository;

  /** {@inheritDoc} */
  @Override
  public Optional<NotificationPreference> findByUserId(long userId) {
    return jpaRepository.findById(userId).map(this::toDomain);
  }

  /** {@inheritDoc} */
  @Override
  public void upsert(NotificationPreference preference, Instant changedAt) {
    jpaRepository.upsert(preference.userId(), preference.chatPushEnabled(), changedAt);
  }

  /** {@inheritDoc} */
  @Override
  public long deleteByUserId(long userId) {
    return jpaRepository.deleteByUserId(userId);
  }

  /** JPA 엔티티를 영속 기술을 모르는 알림 설정 도메인 값으로 변환한다. */
  private NotificationPreference toDomain(NotificationPreferenceJpaEntity entity) {
    return new NotificationPreference(entity.getUserId(), entity.isChatPushEnabled());
  }
}
