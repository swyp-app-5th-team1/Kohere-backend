package com.kohere.notification.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.kohere.notification.domain.NotificationPreference;
import com.kohere.notification.domain.NotificationPreferenceRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Flyway V31과 알림 설정 JPA adapter를 실제 MySQL 8에서 함께 검증한다.
 *
 * <p>사용자 자연 키, BOOLEAN 값, MySQL upsert와 원래 생성 시각 보존을 운영 DB와 같은 엔진에서 확인한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(NotificationPreferenceRepositoryImpl.class)
@Testcontainers
class NotificationPreferencePersistenceIntegrationTest {

  /** Flyway와 Hibernate가 운영과 동일한 MySQL 8 스키마를 사용하도록 테스트 데이터소스를 제공한다. */
  @Container @ServiceConnection
  static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

  @Autowired private NotificationPreferenceRepository repository;
  @Autowired private EntityManager entityManager;
  @Autowired private JdbcTemplate jdbcTemplate;

  /** 설정을 저장하지 않은 사용자는 영속 포트에서 빈 값으로 구분되는지 확인한다. */
  @Test
  void missingUserHasNoStoredPreference() {
    assertThat(repository.findByUserId(10L)).isEmpty();
  }

  /** 최초 false를 INSERT하고 이후 true를 같은 사용자 행에 UPDATE하며 created_at은 보존한다. */
  @Test
  void upsertCreatesThenUpdatesSingleUserRow() {
    Instant firstChange = Instant.parse("2026-09-06T01:00:00Z");
    Instant secondChange = Instant.parse("2026-09-06T02:00:00Z");
    repository.upsert(new NotificationPreference(10L, false), firstChange);
    entityManager.flush();
    entityManager.clear();

    assertThat(repository.findByUserId(10L).orElseThrow().chatPushEnabled()).isFalse();

    repository.upsert(new NotificationPreference(10L, true), secondChange);
    entityManager.flush();
    entityManager.clear();

    NotificationPreference updated = repository.findByUserId(10L).orElseThrow();
    assertThat(updated.chatPushEnabled()).isTrue();
    assertThat(rowCount(10L)).isEqualTo(1L);
    assertThat(timestamp("created_at", 10L)).isEqualTo(firstChange);
    assertThat(timestamp("updated_at", 10L)).isEqualTo(secondChange);
  }

  /** 한 사용자의 변경이 다른 사용자의 알림 설정에 영향을 주지 않는지 확인한다. */
  @Test
  void keepsPreferencesIsolatedByUser() {
    Instant changedAt = Instant.parse("2026-09-06T01:00:00Z");
    repository.upsert(new NotificationPreference(10L, false), changedAt);
    repository.upsert(new NotificationPreference(20L, true), changedAt);
    entityManager.flush();
    entityManager.clear();

    assertThat(repository.findByUserId(10L).orElseThrow().chatPushEnabled()).isFalse();
    assertThat(repository.findByUserId(20L).orElseThrow().chatPushEnabled()).isTrue();
  }

  /** 회원 탈퇴 정리는 행이 있거나 없어도 안전하게 반복할 수 있는지 확인한다. */
  @Test
  void deleteByUserIdIsIdempotent() {
    repository.upsert(
        new NotificationPreference(10L, false), Instant.parse("2026-09-06T01:00:00Z"));
    entityManager.flush();

    assertThat(repository.deleteByUserId(10L)).isEqualTo(1L);
    assertThat(repository.deleteByUserId(10L)).isZero();
  }

  /** 지정한 사용자의 실제 테이블 행 수를 확인한다. */
  private long rowCount(long userId) {
    Long count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM notification_preferences WHERE user_id = ?", Long.class, userId);
    return count == null ? 0 : count;
  }

  /** 지정한 사용자 행의 UTC timestamp 컬럼을 Instant로 읽는다. */
  private Instant timestamp(String column, long userId) {
    LocalDateTime value =
        jdbcTemplate.queryForObject(
            "SELECT " + column + " FROM notification_preferences WHERE user_id = ?",
            LocalDateTime.class,
            userId);
    return value == null ? null : value.toInstant(ZoneOffset.UTC);
  }
}
