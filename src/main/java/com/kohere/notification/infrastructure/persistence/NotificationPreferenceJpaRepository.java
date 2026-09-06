package com.kohere.notification.infrastructure.persistence;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data JPA가 {@code notification_preferences}에 실행할 내부 쿼리를 선언한다. */
interface NotificationPreferenceJpaRepository
    extends JpaRepository<NotificationPreferenceJpaEntity, Long> {

  /**
   * 최초 변경 요청의 INSERT와 이후 요청의 UPDATE를 한 SQL로 처리한다.
   *
   * <p>같은 사용자가 여러 기기에서 최초 변경을 동시에 보내도 PK 충돌 대신 마지막으로 DB가 처리한 값이 남는다. 기존 행의 {@code created_at}은
   * 보존한다.
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      value =
          """
          INSERT INTO notification_preferences (
              user_id, chat_push_enabled, created_at, updated_at
          ) VALUES (
              :userId, :chatPushEnabled, :changedAt, :changedAt
          )
          ON DUPLICATE KEY UPDATE
              chat_push_enabled = :chatPushEnabled,
              updated_at = :changedAt
          """,
      nativeQuery = true)
  void upsert(
      @Param("userId") long userId,
      @Param("chatPushEnabled") boolean chatPushEnabled,
      @Param("changedAt") Instant changedAt);

  /** 회원 탈퇴 사용자의 설정 행을 멱등하게 삭제한다. */
  long deleteByUserId(Long userId);
}
