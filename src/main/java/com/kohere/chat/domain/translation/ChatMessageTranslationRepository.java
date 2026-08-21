package com.kohere.chat.domain.translation;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 번역 작업과 최종 결과를 MySQL에 저장하는 도메인 포트다. */
public interface ChatMessageTranslationRepository {

  /** PENDING 생성 또는 상태 변경 결과를 저장한다. */
  ChatMessageTranslation save(ChatMessageTranslation translation);

  /** 작업 하나를 다른 Worker와 동시에 변경하지 않도록 비관적 잠금으로 조회한다. */
  Optional<ChatMessageTranslation> findByIdForUpdate(Long translationId);

  /** 테스트와 결과 확인에 사용할 일반 단건 조회다. */
  Optional<ChatMessageTranslation> findById(Long translationId);

  /** 같은 원문·수신자·대상 언어의 중복 작업이 이미 존재하는지 확인한다. */
  Optional<ChatMessageTranslation> findByMessageIdAndRecipientUserIdAndTargetLanguage(
      Long messageId, Long recipientUserId, String targetLanguage);

  /** REST 이력 페이지의 여러 원문에 현재 사용자용 번역 결과를 한 번에 붙인다. */
  List<ChatMessageTranslation> findByMessageIdsAndRecipientUserId(
      Collection<Long> messageIds, Long recipientUserId);

  /** 즉시 처리 신호를 놓친 PENDING과 lease가 끝난 PROCESSING 작업 ID를 오래된 순으로 찾는다. */
  List<Long> findRecoverableIds(Instant now, int maxAttempts, int size);
}
