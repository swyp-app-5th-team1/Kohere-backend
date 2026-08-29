package com.kohere.notification.infrastructure.persistence;

import com.kohere.notification.domain.PushDevice;
import com.kohere.notification.domain.PushDeviceRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/** 도메인 {@link PushDeviceRepository}를 Spring Data JPA와 MySQL 저장으로 연결하는 어댑터다. */
@Repository
@RequiredArgsConstructor
public class PushDeviceRepositoryImpl implements PushDeviceRepository {

  private final PushDeviceJpaRepository jpaRepository;

  /** {@inheritDoc} */
  @Override
  public PushDevice save(PushDevice device) {
    return toDomain(jpaRepository.save(toEntity(device)));
  }

  /** {@inheritDoc} */
  @Override
  public Optional<PushDevice> findByInstallationIdForUpdate(UUID installationId) {
    return jpaRepository.findByInstallationIdForUpdate(installationId).map(this::toDomain);
  }

  /** {@inheritDoc} */
  @Override
  public Optional<PushDevice> findByFcmToken(String fcmToken) {
    return jpaRepository.findByFcmToken(fcmToken).map(this::toDomain);
  }

  /** {@inheritDoc} */
  @Override
  public Optional<PushDevice> findByFcmTokenForUpdate(String fcmToken) {
    return jpaRepository.findByFcmTokenForUpdate(fcmToken).map(this::toDomain);
  }

  /** {@inheritDoc} */
  @Override
  public List<PushDevice> findAllByUserId(long userId) {
    return jpaRepository.findAllByUserIdOrderByIdAsc(userId).stream().map(this::toDomain).toList();
  }

  /** {@inheritDoc} */
  @Override
  public boolean deleteByInstallationIdAndUserId(UUID installationId, long userId) {
    return jpaRepository.deleteByInstallationIdAndUserId(installationId, userId) > 0;
  }

  /** {@inheritDoc} */
  @Override
  public boolean deleteByFcmToken(String fcmToken) {
    return jpaRepository.deleteByFcmToken(fcmToken) > 0;
  }

  /** {@inheritDoc} */
  @Override
  public long deleteAllByUserId(long userId) {
    return jpaRepository.deleteAllByUserId(userId);
  }

  /** JPA 엔티티를 Firebase SDK와 영속 기술을 모르는 도메인 객체로 복원한다. */
  private PushDevice toDomain(PushDeviceJpaEntity entity) {
    return PushDevice.builder()
        .id(entity.getId())
        .userId(entity.getUserId())
        .installationId(entity.getInstallationId())
        .fcmToken(entity.getFcmToken())
        .platform(entity.getPlatform())
        .lastSeenAt(entity.getLastSeenAt())
        .createdAt(entity.getCreatedAt())
        .updatedAt(entity.getUpdatedAt())
        .build();
  }

  /** 도메인 객체를 {@code push_devices} 컬럼에 맞는 JPA 엔티티로 변환한다. */
  private PushDeviceJpaEntity toEntity(PushDevice device) {
    return PushDeviceJpaEntity.builder()
        .id(device.getId())
        .userId(device.getUserId())
        .installationId(device.getInstallationId())
        .fcmToken(device.getFcmToken())
        .platform(device.getPlatform())
        .lastSeenAt(device.getLastSeenAt())
        .createdAt(device.getCreatedAt())
        .updatedAt(device.getUpdatedAt())
        .build();
  }
}
