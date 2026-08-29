package com.kohere.notification.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kohere.notification.domain.PushDevice;
import com.kohere.notification.domain.PushDeviceRepository;
import com.kohere.notification.domain.PushPlatform;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Flyway V30과 기기 JPA 어댑터를 실제 MySQL 8에서 함께 검증한다.
 *
 * <p>H2로 대체하지 않고 UUID BINARY(16), ASCII binary collation, CHECK와 UNIQUE를 운영과 같은 엔진에서 확인한다. {@link
 * DataJpaTest}가 각 테스트를 트랜잭션으로 격리하고 종료 시 롤백한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(PushDeviceRepositoryImpl.class)
@Testcontainers
class PushDevicePersistenceIntegrationTest {

  /** Flyway와 Hibernate가 운영과 동일한 MySQL 8 스키마를 사용하도록 테스트 데이터소스를 제공한다. */
  @Container @ServiceConnection
  static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

  /** 테스트 대상인 도메인 영속 포트다. */
  @Autowired private PushDeviceRepository repository;

  /** 1차 캐시를 비워 실제 MySQL에서 UUID와 시각을 다시 읽는 데 사용한다. */
  @Autowired private EntityManager entityManager;

  /** UUID 물리 길이처럼 도메인 포트에서 의도적으로 숨긴 스키마를 확인하는 테스트 도구다. */
  @Autowired private JdbcTemplate jdbcTemplate;

  /** 기기를 저장한 뒤 모든 필드를 복원하고 installation UUID를 실제 16바이트로 보관하는지 확인한다. */
  @Test
  void saveAndFindRoundTripsBinaryInstallationId() {
    UUID installationId = UUID.fromString("07db2e6d-d522-48f0-8fea-8fa490872a49");
    Instant registeredAt = Instant.parse("2026-08-29T06:30:00Z");
    PushDevice saved =
        repository.save(newDevice(10L, installationId, "case-Sensitive-Token", registeredAt));
    entityManager.flush();
    entityManager.clear();

    PushDevice restored = repository.findByInstallationIdForUpdate(installationId).orElseThrow();

    assertThat(restored.getId()).isEqualTo(saved.getId());
    assertThat(restored.getUserId()).isEqualTo(10L);
    assertThat(restored.getInstallationId()).isEqualTo(installationId);
    assertThat(restored.getFcmToken()).isEqualTo("case-Sensitive-Token");
    assertThat(restored.getPlatform()).isEqualTo(PushPlatform.IOS);
    assertThat(restored.getLastSeenAt()).isEqualTo(registeredAt);
    assertThat(restored.getCreatedAt()).isEqualTo(registeredAt);
    assertThat(restored.getUpdatedAt()).isEqualTo(registeredAt);

    Integer storedUuidBytes =
        jdbcTemplate.queryForObject(
            "SELECT OCTET_LENGTH(installation_id) FROM push_devices WHERE id = ?",
            Integer.class,
            saved.getId());
    assertThat(storedUuidBytes).isEqualTo(16);
  }

  /** 한 사용자의 여러 기기는 모두 저장하고 다른 사용자의 기기는 결과에서 제외하는지 확인한다. */
  @Test
  void findAllByUserIdReturnsEveryOwnedInstallation() {
    repository.save(
        newDevice(
            10L,
            UUID.fromString("b60bf570-7770-45fa-8ae1-a537f1147a10"),
            "Token-A",
            Instant.parse("2026-08-29T06:30:00Z")));
    repository.save(
        newDevice(
            10L,
            UUID.fromString("1b17bc9f-6678-4982-be40-8c0b726e7ec2"),
            "token-a",
            Instant.parse("2026-08-29T06:31:00Z")));
    repository.save(
        newDevice(
            20L,
            UUID.fromString("11d71a1c-44b1-41d0-a3ec-a56a8276cc9f"),
            "token-other-user",
            Instant.parse("2026-08-29T06:32:00Z")));
    entityManager.flush();
    entityManager.clear();

    List<PushDevice> devices = repository.findAllByUserId(10L);

    assertThat(devices).extracting(PushDevice::getFcmToken).containsExactly("Token-A", "token-a");
  }

  /** 같은 installation UUID를 다른 토큰으로 다시 INSERT해도 DB UNIQUE가 최종 거부하는지 확인한다. */
  @Test
  void duplicateInstallationIdIsRejected() {
    UUID installationId = UUID.fromString("953d234b-c38c-4b26-820a-d3672d9de105");
    repository.save(
        newDevice(
            10L, installationId, "installation-token-1", Instant.parse("2026-08-29T06:30:00Z")));
    entityManager.flush();

    assertThatThrownBy(
            () -> {
              repository.save(
                  newDevice(
                      20L,
                      installationId,
                      "installation-token-2",
                      Instant.parse("2026-08-29T06:31:00Z")));
              entityManager.flush();
            })
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  /** 같은 FCM 토큰을 다른 installation에 INSERT해도 중복 발송을 막는 DB UNIQUE가 거부하는지 확인한다. */
  @Test
  void duplicateFcmTokenIsRejected() {
    repository.save(
        newDevice(
            10L,
            UUID.fromString("f4454489-ab52-4873-864d-2a8a64888d05"),
            "same-fcm-token",
            Instant.parse("2026-08-29T06:30:00Z")));
    entityManager.flush();

    assertThatThrownBy(
            () -> {
              repository.save(
                  newDevice(
                      20L,
                      UUID.fromString("c28d5899-af4a-4b59-819a-197da70a1ea7"),
                      "same-fcm-token",
                      Instant.parse("2026-08-29T06:31:00Z")));
              entityManager.flush();
            })
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  /** installation 삭제는 소유 사용자가 일치할 때만 실행하고 사용자 전체 삭제는 남은 기기를 모두 정리하는지 확인한다. */
  @Test
  void deleteScopesInstallationToOwnerAndSupportsUserCleanup() {
    UUID firstInstallation = UUID.fromString("61a3cc9f-3dc0-47b8-8621-6537b3868326");
    UUID secondInstallation = UUID.fromString("d8fc0a7c-1455-4e44-949c-11f492fbcecc");
    repository.save(
        newDevice(10L, firstInstallation, "delete-token-1", Instant.parse("2026-08-29T06:30:00Z")));
    repository.save(
        newDevice(
            10L, secondInstallation, "delete-token-2", Instant.parse("2026-08-29T06:31:00Z")));
    entityManager.flush();

    assertThat(repository.deleteByInstallationIdAndUserId(firstInstallation, 20L)).isFalse();
    assertThat(repository.deleteByInstallationIdAndUserId(firstInstallation, 10L)).isTrue();
    assertThat(repository.deleteAllByUserId(10L)).isEqualTo(1L);
    entityManager.flush();
    entityManager.clear();

    assertThat(repository.findAllByUserId(10L)).isEmpty();
  }

  /** FCM이 영구 무효라고 알려 준 토큰만 삭제하고 모르는 토큰은 멱등하게 건너뛰는지 확인한다. */
  @Test
  void deleteByFcmTokenRemovesOnlyMatchingDevice() {
    repository.save(
        newDevice(
            10L,
            UUID.fromString("7e5f4889-d528-4df4-80a1-16f32295edb9"),
            "expired-token",
            Instant.parse("2026-08-29T06:30:00Z")));
    entityManager.flush();

    assertThat(repository.deleteByFcmToken("unknown-token")).isFalse();
    assertThat(repository.deleteByFcmToken("expired-token")).isTrue();
    entityManager.flush();

    assertThat(repository.findByFcmToken("expired-token")).isEmpty();
  }

  /** 토큰 소유 행을 쓰기 잠금으로 조회해 서비스의 중복 토큰 정리 경로가 실제 MySQL에서 동작하는지 확인한다. */
  @Test
  void findByFcmTokenForUpdateReturnsMatchingDevice() {
    UUID installationId = UUID.fromString("80589189-c4ba-4132-9b13-3f3bd23c44b4");
    repository.save(
        newDevice(10L, installationId, "locked-token", Instant.parse("2026-08-29T06:30:00Z")));
    entityManager.flush();
    entityManager.clear();

    PushDevice locked = repository.findByFcmTokenForUpdate("locked-token").orElseThrow();

    assertThat(locked.getInstallationId()).isEqualTo(installationId);
  }

  /** 테스트마다 필요한 사용자·설치본·토큰을 같은 시각으로 초기화한 신규 도메인 객체를 만든다. */
  private PushDevice newDevice(
      long userId, UUID installationId, String fcmToken, Instant registeredAt) {
    return PushDevice.register(userId, installationId, fcmToken, PushPlatform.IOS, registeredAt);
  }
}
