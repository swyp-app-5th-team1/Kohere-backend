package com.kohere.chat.infrastructure.translation.persistence;

import com.kohere.chat.domain.translation.ChatMessageTranslation;
import com.kohere.chat.domain.translation.ChatMessageTranslationRepository;
import com.kohere.chat.domain.translation.ChatTranslationStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

/** 번역 도메인 저장 포트와 Spring Data JPA를 연결하는 영속 어댑터다. */
@Repository
@RequiredArgsConstructor
public class ChatMessageTranslationRepositoryImpl implements ChatMessageTranslationRepository {

  /** 실제 SQL 실행을 담당하는 내부 저장소. */
  private final ChatMessageTranslationJpaRepository jpaRepository;

  /** {@inheritDoc} */
  @Override
  public ChatMessageTranslation save(ChatMessageTranslation translation) {
    return toDomain(jpaRepository.save(toEntity(translation)));
  }

  /** {@inheritDoc} */
  @Override
  public Optional<ChatMessageTranslation> findByIdForUpdate(Long translationId) {
    return jpaRepository.findByIdForUpdate(translationId).map(this::toDomain);
  }

  /** {@inheritDoc} */
  @Override
  public Optional<ChatMessageTranslation> findById(Long translationId) {
    return jpaRepository.findById(translationId).map(this::toDomain);
  }

  /** {@inheritDoc} */
  @Override
  public Optional<ChatMessageTranslation> findByMessageIdAndRecipientUserIdAndTargetLanguage(
      Long messageId, Long recipientUserId, String targetLanguage) {
    return jpaRepository
        .findByMessageIdAndRecipientUserIdAndTargetLanguage(
            messageId, recipientUserId, targetLanguage)
        .map(this::toDomain);
  }

  /** {@inheritDoc} */
  @Override
  public List<ChatMessageTranslation> findByMessageIdsAndRecipientUserId(
      Collection<Long> messageIds, Long recipientUserId) {
    if (messageIds.isEmpty()) {
      return List.of();
    }
    return jpaRepository.findByMessageIdInAndRecipientUserId(messageIds, recipientUserId).stream()
        .map(this::toDomain)
        .toList();
  }

  /** {@inheritDoc} */
  @Override
  public List<Long> findRecoverableIds(Instant now, int maxAttempts, int size) {
    return jpaRepository.findRecoverableIds(
        now,
        maxAttempts,
        ChatTranslationStatus.PENDING,
        ChatTranslationStatus.PROCESSING,
        PageRequest.of(0, size));
  }

  /** {@inheritDoc} */
  @Override
  public List<Long> findExpiredExhaustedIds(Instant now, int maxAttempts, int size) {
    return jpaRepository.findExpiredExhaustedIds(
        now, maxAttempts, ChatTranslationStatus.PROCESSING, PageRequest.of(0, size));
  }

  /** JPA 행을 외부 기술에 의존하지 않는 도메인 객체로 복원한다. */
  private ChatMessageTranslation toDomain(ChatMessageTranslationJpaEntity entity) {
    return ChatMessageTranslation.builder()
        .id(entity.getId())
        .messageId(entity.getMessageId())
        .recipientUserId(entity.getRecipientUserId())
        .targetLanguage(entity.getTargetLanguage())
        .detectedSourceLanguage(entity.getDetectedSourceLanguage())
        .status(entity.getStatus())
        .translatedContent(entity.getTranslatedContent())
        .provider(entity.getProvider())
        .model(entity.getModel())
        .attemptCount(entity.getAttemptCount())
        .leaseUntil(entity.getLeaseUntil())
        .lastFailureCode(entity.getLastFailureCode())
        .translatedAt(entity.getTranslatedAt())
        .createdAt(entity.getCreatedAt())
        .updatedAt(entity.getUpdatedAt())
        .build();
  }

  /** 도메인 객체를 Hibernate가 저장할 JPA 엔티티로 변환한다. */
  private ChatMessageTranslationJpaEntity toEntity(ChatMessageTranslation domain) {
    return ChatMessageTranslationJpaEntity.builder()
        .id(domain.getId())
        .messageId(domain.getMessageId())
        .recipientUserId(domain.getRecipientUserId())
        .targetLanguage(domain.getTargetLanguage())
        .detectedSourceLanguage(domain.getDetectedSourceLanguage())
        .status(domain.getStatus())
        .translatedContent(domain.getTranslatedContent())
        .provider(domain.getProvider())
        .model(domain.getModel())
        .attemptCount(domain.getAttemptCount())
        .leaseUntil(domain.getLeaseUntil())
        .lastFailureCode(domain.getLastFailureCode())
        .translatedAt(domain.getTranslatedAt())
        .createdAt(domain.getCreatedAt())
        .updatedAt(domain.getUpdatedAt())
        .build();
  }
}
