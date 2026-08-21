package com.kohere.chat.infrastructure.translation.persistence;

import com.kohere.chat.domain.translation.ChatTranslationStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data가 번역 작업의 SQL과 비관적 잠금을 생성하게 하는 내부 저장소다. */
interface ChatMessageTranslationJpaRepository
    extends JpaRepository<ChatMessageTranslationJpaEntity, Long> {

  /** 상태 전이를 직렬화하기 위해 번역 작업 한 행을 {@code SELECT ... FOR UPDATE}로 읽는다. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select translation from ChatMessageTranslationJpaEntity translation where translation.id = :id")
  Optional<ChatMessageTranslationJpaEntity> findByIdForUpdate(@Param("id") Long id);

  /** DB UNIQUE와 같은 키로 기존 작업을 확인한다. */
  Optional<ChatMessageTranslationJpaEntity> findByMessageIdAndRecipientUserIdAndTargetLanguage(
      Long messageId, Long recipientUserId, String targetLanguage);

  /** 메시지 이력 페이지의 여러 결과를 한 번에 가져와 N+1 조회를 막는다. */
  List<ChatMessageTranslationJpaEntity> findByMessageIdInAndRecipientUserId(
      Collection<Long> messageIds, Long recipientUserId);

  /**
   * 즉시 실행 신호를 놓친 PENDING과 lease가 끝난 PROCESSING 작업 번호만 조회한다.
   *
   * <p>여기서는 ID 후보만 읽고 실제 소유권은 {@link #findByIdForUpdate(Long)}로 다시 확인한다. 여러 Worker가 같은 후보를 봐도 한 행의
   * 잠금 안에서 한 실행만 claim할 수 있다.
   */
  @Query(
      """
      select translation.id
      from ChatMessageTranslationJpaEntity translation
      where translation.attemptCount < :maxAttempts
        and (
          translation.status = :pending
          or (
            translation.status = :processing
            and translation.leaseUntil <= :now
          )
        )
      order by translation.id asc
      """)
  List<Long> findRecoverableIds(
      @Param("now") Instant now,
      @Param("maxAttempts") int maxAttempts,
      @Param("pending") ChatTranslationStatus pending,
      @Param("processing") ChatTranslationStatus processing,
      Pageable pageable);
}
