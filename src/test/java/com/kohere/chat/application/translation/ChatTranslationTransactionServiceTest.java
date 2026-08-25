package com.kohere.chat.application.translation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.kohere.chat.domain.Message;
import com.kohere.chat.domain.MessageRepository;
import com.kohere.chat.domain.MessageType;
import com.kohere.chat.domain.translation.ChatMessageTranslation;
import com.kohere.chat.domain.translation.ChatMessageTranslationRepository;
import com.kohere.chat.domain.translation.ChatTranslationStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 최대 호출 횟수에서 중단된 번역을 행 잠금 안에서 안전하게 종결하는지 검증한다. */
@ExtendWith(MockitoExtension.class)
class ChatTranslationTransactionServiceTest {

  private static final long TRANSLATION_ID = 801L;
  private static final long MESSAGE_ID = 501L;
  private static final Instant NOW = Instant.parse("2026-08-25T07:00:00Z");

  @Mock private ChatMessageTranslationRepository translationRepository;
  @Mock private MessageRepository messageRepository;

  private ChatTranslationTransactionService service;

  @BeforeEach
  void setUp() {
    service = new ChatTranslationTransactionService(translationRepository, messageRepository);
  }

  /** lease가 끝났고 다섯 번을 모두 사용한 작업은 Google을 다시 호출하지 않도록 FAILED로 바꾼다. */
  @Test
  @DisplayName("만료된 최대 시도 PROCESSING 작업을 FAILED로 종결한다")
  void completesExpiredExhaustedWork() {
    ChatMessageTranslation stuck = processing(5, NOW.minusSeconds(60));
    Message original = original();
    given(translationRepository.findByIdForUpdate(TRANSLATION_ID)).willReturn(Optional.of(stuck));
    given(messageRepository.findById(MESSAGE_ID)).willReturn(Optional.of(original));
    given(translationRepository.save(any(ChatMessageTranslation.class)))
        .willAnswer(invocation -> invocation.getArgument(0));

    Optional<ChatTranslationCompletion> result =
        service.completeExpiredExhausted(TRANSLATION_ID, NOW, 5);

    assertThat(result).isPresent();
    ChatMessageTranslation completed = result.orElseThrow().translation();
    assertThat(completed.getStatus()).isEqualTo(ChatTranslationStatus.FAILED);
    assertThat(completed.getLastFailureCode()).isEqualTo("MAX_ATTEMPTS_EXHAUSTED");
    assertThat(completed.getLeaseUntil()).isNull();
    assertThat(completed.getTranslatedAt()).isEqualTo(NOW);
  }

  /** 아직 provider가 실행될 수 있는 lease 안에서는 복구 scheduler가 상태를 가로채지 않는다. */
  @Test
  @DisplayName("lease가 남은 최대 시도 작업은 변경하지 않는다")
  void ignoresActiveExhaustedWork() {
    ChatMessageTranslation active = processing(5, NOW);
    given(translationRepository.findByIdForUpdate(TRANSLATION_ID)).willReturn(Optional.of(active));

    Optional<ChatTranslationCompletion> result =
        service.completeExpiredExhausted(TRANSLATION_ID, NOW, 5);

    assertThat(result).isEmpty();
    verify(messageRepository, never()).findById(any());
    verify(translationRepository, never()).save(any());
  }

  /** 지정한 횟수의 호출이 시작된 PROCESSING 작업 fixture를 만든다. */
  private static ChatMessageTranslation processing(int attemptCount, Instant attemptStartedAt) {
    ChatMessageTranslation translation =
        ChatMessageTranslation.pending(MESSAGE_ID, 77L, "en", attemptStartedAt.minusSeconds(1))
            .toBuilder()
            .id(TRANSLATION_ID)
            .build()
            .claim(attemptStartedAt, Duration.ofSeconds(30));
    for (int attempt = 0; attempt < attemptCount; attempt++) {
      translation = translation.beginAttempt(attemptStartedAt, Duration.ofSeconds(30), 5);
    }
    return translation;
  }

  /** 완료 이벤트에 함께 실을 원문 TEXT fixture다. */
  private static Message original() {
    return Message.builder()
        .id(MESSAGE_ID)
        .chatRoomId(10L)
        .senderId(42L)
        .type(MessageType.TEXT)
        .content("안녕하세요")
        .clientMessageId(UUID.fromString("b6506eb7-bf2d-47c8-a8d2-5f75cdb6d849"))
        .sentAt(NOW.minusSeconds(61))
        .build();
  }
}
