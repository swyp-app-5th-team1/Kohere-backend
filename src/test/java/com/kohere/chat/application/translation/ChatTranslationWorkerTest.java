package com.kohere.chat.application.translation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.kohere.chat.domain.Message;
import com.kohere.chat.domain.MessageType;
import com.kohere.chat.domain.TranslationProvider;
import com.kohere.chat.domain.translation.ChatMessageTranslation;
import com.kohere.chat.domain.translation.ChatTranslationStatus;
import com.kohere.chat.infrastructure.translation.ChatTranslationProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.SyncTaskExecutor;

/** 외부 네트워크 없이 Worker의 성공·불필요·실패·최대 5회 재시도 결정을 검증한다. */
@ExtendWith(MockitoExtension.class)
class ChatTranslationWorkerTest {

  private static final long TRANSLATION_ID = 801L;
  private static final long MESSAGE_ID = 501L;

  @Mock private ChatTranslationTransactionService transactions;
  @Mock private ChatTranslationClient translationClient;
  @Mock private ChatTranslationResultPublisher resultPublisher;

  private ChatTranslationProperties properties;
  private ChatTranslationWorker worker;

  @BeforeEach
  void setUp() {
    properties = new ChatTranslationProperties();
    properties.setMaxAttempts(5);
    properties.setDeliveryTimeout(Duration.ofSeconds(30));
    properties.setLeaseDuration(Duration.ofSeconds(30));
    // 단위 테스트는 실제 시간을 기다릴 이유가 없으므로 재시도 간격만 0으로 둔다.
    properties.setInitialBackoff(Duration.ZERO);
    worker =
        new ChatTranslationWorker(
            new SyncTaskExecutor(), properties, transactions, translationClient, resultPublisher);
  }

  /** 한 번의 Google 성공을 SUCCEEDED로 저장한 뒤 원문과 함께 발행한다. */
  @Test
  @DisplayName("번역 성공 결과를 저장하고 수신자에게 발행한다")
  void completesSuccessfulTranslation() {
    ChatTranslationWorkItem claimed = workItem(0, "en");
    ChatTranslationWorkItem attempt = workItem(1, "en");
    ChatTranslationCompletion completion = completion(ChatTranslationStatus.SUCCEEDED, "Hello");
    given(transactions.claim(eq(TRANSLATION_ID), any(), any(), eq(5)))
        .willReturn(Optional.of(claimed));
    given(transactions.beginAttempt(eq(TRANSLATION_ID), any(), any(), eq(5)))
        .willReturn(Optional.of(attempt));
    given(translationClient.translate("안녕하세요", "en"))
        .willReturn(new ChatTranslationClientResult("Hello", "ko"));
    given(transactions.completeSuccess(eq(TRANSLATION_ID), eq("ko"), eq("Hello"), any()))
        .willReturn(Optional.of(completion));

    worker.process(TRANSLATION_ID);

    verify(resultPublisher).publish(completion.originalMessage(), completion.translation());
    verify(translationClient).translate("안녕하세요", "en");
  }

  /** 원문 언어와 대상 언어가 같으면 별도 번역문 없이 NOT_REQUIRED로 끝낸다. */
  @Test
  @DisplayName("같은 언어는 NOT_REQUIRED로 완료한다")
  void completesNotRequiredWhenLanguagesMatch() {
    given(transactions.claim(eq(TRANSLATION_ID), any(), any(), eq(5)))
        .willReturn(Optional.of(workItem(0, "ko")));
    given(transactions.beginAttempt(eq(TRANSLATION_ID), any(), any(), eq(5)))
        .willReturn(Optional.of(workItem(1, "ko")));
    given(translationClient.translate("안녕하세요", "ko"))
        .willReturn(new ChatTranslationClientResult("안녕하세요", "ko-KR"));
    ChatTranslationCompletion completion = completion(ChatTranslationStatus.NOT_REQUIRED, null);
    given(transactions.completeNotRequired(eq(TRANSLATION_ID), eq("ko"), any()))
        .willReturn(Optional.of(completion));

    worker.process(TRANSLATION_ID);

    verify(resultPublisher).publish(completion.originalMessage(), completion.translation());
  }

  /** 인증·설정처럼 재시도해도 해결되지 않는 오류는 한 번 호출하고 바로 FAILED로 끝낸다. */
  @Test
  @DisplayName("영구 오류는 한 번만 호출하고 실패한다")
  void failsPermanentErrorWithoutRetry() {
    given(transactions.claim(eq(TRANSLATION_ID), any(), any(), eq(5)))
        .willReturn(Optional.of(workItem(0, "en")));
    given(transactions.beginAttempt(eq(TRANSLATION_ID), any(), any(), eq(5)))
        .willReturn(Optional.of(workItem(1, "en")));
    given(translationClient.translate("안녕하세요", "en"))
        .willThrow(new ChatTranslationClientException("GOOGLE_PERMISSION_DENIED", false, null));
    ChatTranslationCompletion completion = completion(ChatTranslationStatus.FAILED, null);
    given(transactions.completeFailure(eq(TRANSLATION_ID), eq("GOOGLE_PERMISSION_DENIED"), any()))
        .willReturn(Optional.of(completion));

    worker.process(TRANSLATION_ID);

    verify(translationClient, times(1)).translate("안녕하세요", "en");
    verify(resultPublisher).publish(completion.originalMessage(), completion.translation());
  }

  /** timeout 같은 일시 오류도 최초 호출을 포함해 정확히 다섯 번까지만 요청한다. */
  @Test
  @DisplayName("재시도 가능한 오류는 최대 다섯 번 호출한다")
  void retriesTransientFailureAtMostFiveTimes() {
    given(transactions.claim(eq(TRANSLATION_ID), any(), any(), eq(5)))
        .willReturn(Optional.of(workItem(0, "en")));
    given(transactions.beginAttempt(eq(TRANSLATION_ID), any(), any(), eq(5)))
        .willReturn(
            Optional.of(workItem(1, "en")),
            Optional.of(workItem(2, "en")),
            Optional.of(workItem(3, "en")),
            Optional.of(workItem(4, "en")),
            Optional.of(workItem(5, "en")));
    given(translationClient.translate("안녕하세요", "en"))
        .willThrow(new ChatTranslationClientException("GOOGLE_UNAVAILABLE", true, null));
    ChatTranslationCompletion completion = completion(ChatTranslationStatus.FAILED, null);
    given(transactions.completeFailure(eq(TRANSLATION_ID), eq("GOOGLE_UNAVAILABLE"), any()))
        .willReturn(Optional.of(completion));

    worker.process(TRANSLATION_ID);

    verify(translationClient, times(5)).translate("안녕하세요", "en");
    verify(transactions, times(5)).beginAttempt(eq(TRANSLATION_ID), any(), any(), eq(5));
    verify(resultPublisher).publish(completion.originalMessage(), completion.translation());
  }

  /** 복구 조회가 찾은 작업도 즉시 제출과 같은 처리 메서드로 전달되는지 확인한다. */
  @Test
  @DisplayName("복구 조회는 남은 작업을 executor에 다시 제출한다")
  void submitsRecoverableWork() {
    given(transactions.findRecoverableIds(any(), anyInt(), anyInt()))
        .willReturn(java.util.List.of(801L, 802L));
    // SyncTaskExecutor가 process를 즉시 호출하므로 두 작업 모두 claim 불가로 끝내 테스트를 단순화한다.
    given(transactions.claim(anyLong(), any(), any(), anyInt())).willReturn(Optional.empty());

    worker.recoverUnfinishedWork();

    ArgumentCaptor<Long> ids = ArgumentCaptor.forClass(Long.class);
    verify(transactions, times(2)).claim(ids.capture(), any(), any(), eq(5));
    assertThat(ids.getAllValues()).containsExactly(801L, 802L);
    verifyNoMoreInteractions(resultPublisher);
  }

  /** 지정한 시도 횟수와 대상 언어를 가진 처리 snapshot을 만든다. */
  private static ChatTranslationWorkItem workItem(int attemptCount, String targetLanguage) {
    Instant now = Instant.parse("2026-08-22T01:00:00Z");
    ChatMessageTranslation translation =
        ChatMessageTranslation.builder()
            .id(TRANSLATION_ID)
            .messageId(MESSAGE_ID)
            .recipientUserId(77L)
            .targetLanguage(targetLanguage)
            .status(ChatTranslationStatus.PROCESSING)
            .provider(TranslationProvider.GOOGLE_CLOUD_TRANSLATION)
            .model("NMT")
            .attemptCount(attemptCount)
            .leaseUntil(now.plusSeconds(30))
            .createdAt(now)
            .updatedAt(now)
            .build();
    return new ChatTranslationWorkItem(translation, original());
  }

  /** publisher 검증에 사용할 최종 상태 snapshot을 만든다. */
  private static ChatTranslationCompletion completion(
      ChatTranslationStatus status, String translatedContent) {
    Instant now = Instant.parse("2026-08-22T01:00:01Z");
    ChatMessageTranslation translation =
        workItem(1, "en").translation().toBuilder()
            .status(status)
            .detectedSourceLanguage(status == ChatTranslationStatus.FAILED ? null : "ko")
            .translatedContent(translatedContent)
            .leaseUntil(null)
            .translatedAt(now)
            .updatedAt(now)
            .build();
    return new ChatTranslationCompletion(original(), translation);
  }

  /** 수정되지 않은 원문 TEXT fixture다. */
  private static Message original() {
    return Message.builder()
        .id(MESSAGE_ID)
        .chatRoomId(10L)
        .senderId(42L)
        .type(MessageType.TEXT)
        .content("안녕하세요")
        .clientMessageId(UUID.fromString("b6506eb7-bf2d-47c8-a8d2-5f75cdb6d849"))
        .sentAt(Instant.parse("2026-08-22T01:00:00Z"))
        .build();
  }
}
