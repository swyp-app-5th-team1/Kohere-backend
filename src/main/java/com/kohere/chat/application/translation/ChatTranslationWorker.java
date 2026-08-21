package com.kohere.chat.application.translation;

import com.kohere.chat.infrastructure.translation.ChatTranslationProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 커밋된 번역 작업을 가져와 Google 호출·재시도·결과 저장·개인 전달을 순서대로 관리한다.
 *
 * <p>실제 번역은 {@link ChatTranslationClient}가 한다. Worker는 DB 작업이 유실되지 않게 상태를 관리하고, STOMP inbound
 * thread가 외부 네트워크를 기다리지 않도록 전용 executor에서 실행한다.
 */
@Slf4j
@Component
public class ChatTranslationWorker {

  private final TaskExecutor taskExecutor;
  private final ChatTranslationProperties properties;
  private final ChatTranslationTransactionService transactions;
  private final ChatTranslationClient translationClient;
  private final ChatTranslationResultPublisher resultPublisher;

  public ChatTranslationWorker(
      @Qualifier("chatTranslationTaskExecutor") TaskExecutor taskExecutor,
      ChatTranslationProperties properties,
      ChatTranslationTransactionService transactions,
      ChatTranslationClient translationClient,
      ChatTranslationResultPublisher resultPublisher) {
    this.taskExecutor = taskExecutor;
    this.properties = properties;
    this.transactions = transactions;
    this.translationClient = translationClient;
    this.resultPublisher = resultPublisher;
  }

  /** 원문과 PENDING의 커밋 직후 작업 하나를 즉시 전용 executor에 제출한다. */
  public void submit(long translationId) {
    try {
      taskExecutor.execute(() -> process(translationId));
    } catch (RejectedExecutionException failure) {
      // queue가 가득 차도 PENDING은 DB에 남는다. 원문을 로그에 넣지 않고 복구 scheduler에 처리를 맡긴다.
      log.warn("Chat translation submission deferred: translationId={}", translationId);
    }
  }

  /**
   * 즉시 제출 신호 유실과 서버 재시작을 복구하는 안전망이다.
   *
   * <p>일반 메시지를 60초마다 번역하는 polling이 아니다. 정상 경로는 {@link #submit(long)}으로 즉시 실행된다.
   */
  @Scheduled(fixedDelayString = "${app.chat.translation.recovery-interval:60s}")
  public void recoverUnfinishedWork() {
    Instant now = Instant.now();
    for (Long translationId :
        transactions.findRecoverableIds(
            now, properties.getMaxAttempts(), properties.getRecoveryBatchSize())) {
      submit(translationId);
    }
  }

  /** 번역 작업 하나를 최대 호출 횟수와 전체 시간 예산 안에서 처리한다. */
  void process(long translationId) {
    Instant startedAt = Instant.now();
    Instant deliveryDeadline = startedAt.plus(properties.getDeliveryTimeout());

    Optional<ChatTranslationWorkItem> claimed =
        transactions.claim(
            translationId, startedAt, properties.getLeaseDuration(), properties.getMaxAttempts());
    if (claimed.isEmpty()) {
      return;
    }

    String lastFailureCode = "TRANSLATION_FAILED";
    while (Instant.now().isBefore(deliveryDeadline)) {
      Optional<ChatTranslationWorkItem> attempt =
          transactions.beginAttempt(
              translationId,
              Instant.now(),
              properties.getLeaseDuration(),
              properties.getMaxAttempts());
      if (attempt.isEmpty()) {
        return;
      }

      ChatTranslationWorkItem work = attempt.get();
      try {
        ChatTranslationClientResult providerResult =
            translationClient.translate(
                work.originalMessage().getContent(), work.translation().getTargetLanguage());
        completeProviderResult(
            translationId, providerResult, work.translation().getTargetLanguage());
        return;
      } catch (ChatTranslationClientException failure) {
        lastFailureCode = failure.getFailureCode();
        if (!failure.isRetryable()
            || work.translation().getAttemptCount() >= properties.getMaxAttempts()) {
          publish(transactions.completeFailure(translationId, lastFailureCode, Instant.now()));
          return;
        }

        Duration delay = retryDelay(work.translation().getAttemptCount());
        if (!Instant.now().plus(delay).isBefore(deliveryDeadline)) {
          publish(transactions.completeFailure(translationId, "DELIVERY_TIMEOUT", Instant.now()));
          return;
        }
        if (!sleepBeforeRetry(delay, translationId)) {
          // 종료 중 interrupt라면 PROCESSING lease를 남겨 다음 기동의 복구 조회가 이어서 처리하게 한다.
          return;
        }
      } catch (RuntimeException unexpected) {
        log.error(
            "Unexpected translation provider failure: translationId={}, attempt={}",
            translationId,
            work.translation().getAttemptCount(),
            unexpected);
        publish(
            transactions.completeFailure(
                translationId, "UNEXPECTED_PROVIDER_ERROR", Instant.now()));
        return;
      }
    }

    publish(transactions.completeFailure(translationId, lastFailureCode, Instant.now()));
  }

  /** provider 결과의 감지 언어에 따라 성공 또는 번역 불필요를 확정한다. */
  private void completeProviderResult(
      long translationId, ChatTranslationClientResult result, String targetLanguage) {
    String sourceLanguage = normalizeLanguage(result.detectedSourceLanguage());
    if (targetLanguage.equals(sourceLanguage)) {
      publish(transactions.completeNotRequired(translationId, sourceLanguage, Instant.now()));
      return;
    }
    publish(
        transactions.completeSuccess(
            translationId, sourceLanguage, result.translatedContent(), Instant.now()));
  }

  /** 첫 실패 200ms부터 호출 사이 대기 시간을 두 배씩 늘린다. */
  private Duration retryDelay(int completedAttemptCount) {
    long multiplier = 1L << Math.max(0, completedAttemptCount - 1);
    return properties.getInitialBackoff().multipliedBy(multiplier);
  }

  /** interrupt를 보존하면서 짧은 재시도 대기를 수행한다. */
  private boolean sleepBeforeRetry(Duration delay, long translationId) {
    try {
      Thread.sleep(delay);
      return true;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      log.info("Chat translation interrupted: translationId={}", translationId);
      return false;
    }
  }

  /** DB commit이 끝난 완료 결과만 수신자의 개인 queue로 전달한다. */
  private void publish(Optional<ChatTranslationCompletion> completion) {
    completion.ifPresent(
        value -> resultPublisher.publish(value.originalMessage(), value.translation()));
  }

  /** Google의 지역형 언어 code가 와도 비교와 외부 계약은 ko/en 기본 code로 맞춘다. */
  private static String normalizeLanguage(String language) {
    if (language == null || language.isBlank()) {
      return "und";
    }
    int separator = language.indexOf('-');
    String base = separator < 0 ? language : language.substring(0, separator);
    return base.toLowerCase(java.util.Locale.ROOT);
  }
}
