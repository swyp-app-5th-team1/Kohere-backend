package com.kohere.chat.application.translation;

import com.kohere.chat.domain.Message;
import com.kohere.chat.domain.MessageRepository;
import com.kohere.chat.domain.MessageType;
import com.kohere.chat.domain.translation.ChatMessageTranslation;
import com.kohere.chat.domain.translation.ChatMessageTranslationRepository;
import com.kohere.chat.domain.translation.ChatTranslationStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Translation Worker가 필요한 짧은 DB 트랜잭션을 담당한다.
 *
 * <p>Google 네트워크 호출은 이 클래스 밖에서 실행한다. 따라서 외부 응답을 기다리는 동안 MySQL 행 잠금을 잡아 다른 채팅 저장을 막지 않는다.
 */
@Service
@RequiredArgsConstructor
public class ChatTranslationTransactionService {

  private final ChatMessageTranslationRepository translationRepository;
  private final MessageRepository messageRepository;

  /** PENDING 또는 lease 만료 작업을 한 Worker의 PROCESSING 상태로 확보한다. */
  @Transactional
  public Optional<ChatTranslationWorkItem> claim(
      long translationId, Instant now, Duration leaseDuration, int maxAttempts) {
    ChatMessageTranslation current =
        translationRepository.findByIdForUpdate(translationId).orElse(null);
    if (current == null || !current.canBeClaimed(now, maxAttempts)) {
      return Optional.empty();
    }

    Message original = messageRepository.findById(current.getMessageId()).orElse(null);
    if (original == null || original.getType() != MessageType.TEXT) {
      // no-FK 정책에서 부모 원문이 비정상적으로 없으면 provider에 보낼 내용이 없으므로 안전하게 종결한다.
      ChatMessageTranslation claimed = current.claim(now, leaseDuration);
      translationRepository.save(claimed.fail("ORIGINAL_MESSAGE_MISSING", now));
      return Optional.empty();
    }

    ChatMessageTranslation claimed = translationRepository.save(current.claim(now, leaseDuration));
    return Optional.of(new ChatTranslationWorkItem(claimed, original));
  }

  /** Google 호출 직전에 누적 시도 횟수와 새 lease를 먼저 커밋한다. */
  @Transactional
  public Optional<ChatTranslationWorkItem> beginAttempt(
      long translationId, Instant now, Duration leaseDuration, int maxAttempts) {
    ChatMessageTranslation current =
        translationRepository.findByIdForUpdate(translationId).orElse(null);
    if (current == null
        || current.getStatus() != ChatTranslationStatus.PROCESSING
        || current.getAttemptCount() >= maxAttempts) {
      return Optional.empty();
    }

    Message original = messageRepository.findById(current.getMessageId()).orElse(null);
    if (original == null || original.getType() != MessageType.TEXT) {
      translationRepository.save(current.fail("ORIGINAL_MESSAGE_MISSING", now));
      return Optional.empty();
    }

    ChatMessageTranslation started =
        translationRepository.save(current.beginAttempt(now, leaseDuration, maxAttempts));
    return Optional.of(new ChatTranslationWorkItem(started, original));
  }

  /** 다른 언어 번역문을 저장하고 개인 이벤트에 필요한 완료 snapshot을 반환한다. */
  @Transactional
  public Optional<ChatTranslationCompletion> completeSuccess(
      long translationId, String sourceLanguage, String translatedContent, Instant now) {
    return complete(
        translationId, current -> current.succeed(sourceLanguage, translatedContent, now));
  }

  /** 원문과 대상 언어가 같다는 결과를 저장한다. */
  @Transactional
  public Optional<ChatTranslationCompletion> completeNotRequired(
      long translationId, String sourceLanguage, Instant now) {
    return complete(translationId, current -> current.notRequired(sourceLanguage, now));
  }

  /** 영구 오류·재시도 상한·전체 시간 초과를 FAILED로 저장한다. */
  @Transactional
  public Optional<ChatTranslationCompletion> completeFailure(
      long translationId, String failureCode, Instant now) {
    return complete(translationId, current -> current.fail(failureCode, now));
  }

  /** 복구 scheduler가 처리 후보 ID만 가볍게 조회한다. 실제 소유권은 claim의 잠금이 정한다. */
  @Transactional(readOnly = true)
  public List<Long> findRecoverableIds(Instant now, int maxAttempts, int batchSize) {
    return translationRepository.findRecoverableIds(now, maxAttempts, batchSize);
  }

  /** 같은 최종 상태 저장 절차를 한곳에 모아 중복 publisher 실행을 막는다. */
  private Optional<ChatTranslationCompletion> complete(
      long translationId,
      java.util.function.Function<ChatMessageTranslation, ChatMessageTranslation> transition) {
    ChatMessageTranslation current =
        translationRepository.findByIdForUpdate(translationId).orElse(null);
    if (current == null || current.getStatus() != ChatTranslationStatus.PROCESSING) {
      return Optional.empty();
    }

    Message original = messageRepository.findById(current.getMessageId()).orElse(null);
    if (original == null || original.getType() != MessageType.TEXT) {
      translationRepository.save(current.fail("ORIGINAL_MESSAGE_MISSING", Instant.now()));
      return Optional.empty();
    }

    ChatMessageTranslation completed = translationRepository.save(transition.apply(current));
    return Optional.of(new ChatTranslationCompletion(original, completed));
  }
}
