package com.kohere.notification.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data JPA가 {@code push_devices}에 실행할 내부 쿼리를 선언한다. */
interface PushDeviceJpaRepository extends JpaRepository<PushDeviceJpaEntity, Long> {

  /** 기존 installation의 토큰·사용자 갱신이 서로 덮어쓰지 않도록 행을 쓰기 잠금으로 조회한다. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "SELECT device FROM PushDeviceJpaEntity device WHERE device.installationId = :installationId")
  Optional<PushDeviceJpaEntity> findByInstallationIdForUpdate(
      @Param("installationId") UUID installationId);

  /** FCM 토큰 UNIQUE 충돌 전에 현재 소유 행을 확인한다. */
  Optional<PushDeviceJpaEntity> findByFcmToken(String fcmToken);

  /** 토큰을 다른 installation으로 옮기는 등록 요청이 서로 충돌하지 않도록 현재 소유 행을 쓰기 잠금으로 조회한다. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT device FROM PushDeviceJpaEntity device WHERE device.fcmToken = :fcmToken")
  Optional<PushDeviceJpaEntity> findByFcmTokenForUpdate(@Param("fcmToken") String fcmToken);

  /** 한 사용자의 모든 기기를 내부 ID 오름차순으로 조회한다. */
  List<PushDeviceJpaEntity> findAllByUserIdOrderByIdAsc(Long userId);

  /** 현재 사용자가 소유한 installation 한 건만 삭제한다. */
  long deleteByInstallationIdAndUserId(UUID installationId, Long userId);

  /** FCM이 영구 무효이거나 다른 installation으로 이동한 토큰 한 건을 즉시 삭제한다. */
  @Modifying(flushAutomatically = true)
  @Query("DELETE FROM PushDeviceJpaEntity device WHERE device.fcmToken = :fcmToken")
  long deleteByFcmToken(@Param("fcmToken") String fcmToken);

  /** 회원 탈퇴 사용자의 모든 installation을 삭제한다. */
  long deleteAllByUserId(Long userId);
}
