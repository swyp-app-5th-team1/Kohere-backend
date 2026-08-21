package com.kohere.chat.domain.translation;

import com.kohere.chat.domain.TranslationProvider;
import java.time.Duration;
import java.time.Instant;
import lombok.Builder;
import lombok.Getter;

/**
 * TEXT 한 건을 특정 수신자의 표시 언어로 번역하는 작업이자 저장 결과다.
 *
 * <p>원문은 {@code chat_messages}에 그대로 남고 이 객체에는 번역에 필요한 식별자와 파생 결과만 둔다. 상태 변경 메서드가 새 객체를 반환하므로 한 단계에서
 * 어떤 값이 바뀌는지 쉽게 확인할 수 있다.
 */
@Getter
@Builder(toBuilder = true)
public class ChatMessageTranslation {

  /** DB가 발급하는 번역 작업 번호. 신규 PENDING 생성 전에는 null이다. */
  private final Long id;

  /** 번역할 원문 {@code chat_messages.id}. */
  private final Long messageId;

  /** 원문과 번역본을 함께 받을 상대 사용자의 {@code users.id}. */
  private final Long recipientUserId;

  /** 원문 저장 시점에 확인한 수신자의 표시 언어. 현재 지원 값은 ko와 en이다. */
  private final String targetLanguage;

  /** Google이 실제 본문에서 감지한 원문 언어. 처리 전이나 실패 상태에서는 null일 수 있다. */
  private final String detectedSourceLanguage;

  /** durable 작업 큐와 최종 결과를 함께 표현하는 상태. */
  private final ChatTranslationStatus status;

  /** SUCCEEDED일 때만 존재하는 번역문. 원문을 덮어쓰지 않는다. */
  private final String translatedContent;

  /** 운영 추적과 사용자 출처 표시에 사용하는 외부 제공자. */
  private final TranslationProvider provider;

  /** Google 기본 NMT 모델처럼 결과를 만든 모델 종류. */
  private final String model;

  /** 외부 provider를 실제로 호출하기 직전에 증가하는 누적 횟수. */
  private final int attemptCount;

  /** PROCESSING 작업을 다른 Worker가 동시에 가져가지 못하게 하는 임시 소유 기한. */
  private final Instant leaseUntil;

  /** 본문이나 provider 원문 응답을 포함하지 않는 안전한 실패 분류 code. */
  private final String lastFailureCode;

  /** 성공·불필요·실패 중 한 최종 결과가 확정된 시각. */
  private final Instant translatedAt;

  /** PENDING 행을 처음 만든 시각. */
  private final Instant createdAt;

  /** 상태나 시도 횟수를 마지막으로 변경한 시각. */
  private final Instant updatedAt;

  /** 신규 TEXT와 같은 트랜잭션에 넣을 최초 PENDING 작업을 만든다. */
  public static ChatMessageTranslation pending(
      long messageId, long recipientUserId, String targetLanguage, Instant now) {
    return ChatMessageTranslation.builder()
        .messageId(messageId)
        .recipientUserId(recipientUserId)
        .targetLanguage(targetLanguage)
        .status(ChatTranslationStatus.PENDING)
        .provider(TranslationProvider.GOOGLE_CLOUD_TRANSLATION)
        .model("NMT")
        .attemptCount(0)
        .createdAt(now)
        .updatedAt(now)
        .build();
  }

  /** PENDING 또는 lease가 끝난 PROCESSING 작업인지 확인한다. */
  public boolean canBeClaimed(Instant now, int maxAttempts) {
    if (attemptCount >= maxAttempts) {
      return false;
    }
    if (status == ChatTranslationStatus.PENDING) {
      return true;
    }
    return status == ChatTranslationStatus.PROCESSING
        && leaseUntil != null
        && !leaseUntil.isAfter(now);
  }

  /** Worker가 짧은 DB 트랜잭션에서 작업을 확보하고 외부 호출 전에 lock을 풀 수 있게 한다. */
  public ChatMessageTranslation claim(Instant now, Duration leaseDuration) {
    return toBuilder()
        .status(ChatTranslationStatus.PROCESSING)
        .leaseUntil(now.plus(leaseDuration))
        .updatedAt(now)
        .build();
  }

  /** Google 호출 직전에 시도 횟수를 먼저 저장해 서버 중단 뒤에도 총 호출 상한을 지킨다. */
  public ChatMessageTranslation beginAttempt(Instant now, Duration leaseDuration, int maxAttempts) {
    if (status != ChatTranslationStatus.PROCESSING || attemptCount >= maxAttempts) {
      throw new IllegalStateException("처리 중이며 시도 횟수가 남은 번역만 호출할 수 있습니다.");
    }
    return toBuilder()
        .attemptCount(attemptCount + 1)
        .leaseUntil(now.plus(leaseDuration))
        .updatedAt(now)
        .build();
  }

  /** Google이 다른 언어의 번역문을 반환했을 때 성공 결과를 확정한다. */
  public ChatMessageTranslation succeed(
      String sourceLanguage, String translatedContent, Instant now) {
    if (translatedContent == null || translatedContent.isBlank()) {
      throw new IllegalArgumentException("성공한 번역문은 비어 있을 수 없습니다.");
    }
    return terminalBuilder(sourceLanguage, now)
        .status(ChatTranslationStatus.SUCCEEDED)
        .translatedContent(translatedContent)
        .build();
  }

  /** 감지된 원문 언어가 대상 언어와 같을 때 별도 번역문 없이 완료한다. */
  public ChatMessageTranslation notRequired(String sourceLanguage, Instant now) {
    return terminalBuilder(sourceLanguage, now).status(ChatTranslationStatus.NOT_REQUIRED).build();
  }

  /** 재시도 한도 또는 영구 오류가 발생했을 때 원문을 보낼 수 있는 실패 결과로 끝낸다. */
  public ChatMessageTranslation fail(String failureCode, Instant now) {
    return terminalBuilder(detectedSourceLanguage, now)
        .status(ChatTranslationStatus.FAILED)
        .lastFailureCode(failureCode)
        .build();
  }

  /** REST·STOMP에 공개할 수 있는 최종 상태인지 확인한다. */
  public boolean isTerminal() {
    return status == ChatTranslationStatus.SUCCEEDED
        || status == ChatTranslationStatus.NOT_REQUIRED
        || status == ChatTranslationStatus.FAILED;
  }

  /** 모든 최종 상태가 공통으로 lease와 이전 실패 결과를 지우도록 한곳에서 조립한다. */
  private ChatMessageTranslationBuilder terminalBuilder(String sourceLanguage, Instant now) {
    if (status != ChatTranslationStatus.PROCESSING) {
      throw new IllegalStateException("처리 중인 번역만 최종 상태로 변경할 수 있습니다.");
    }
    return toBuilder()
        .detectedSourceLanguage(sourceLanguage)
        .translatedContent(null)
        .leaseUntil(null)
        .lastFailureCode(null)
        .translatedAt(now)
        .updatedAt(now);
  }
}
