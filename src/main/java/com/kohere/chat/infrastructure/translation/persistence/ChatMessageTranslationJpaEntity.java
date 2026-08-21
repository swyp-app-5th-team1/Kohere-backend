package com.kohere.chat.infrastructure.translation.persistence;

import com.kohere.chat.domain.TranslationProvider;
import com.kohere.chat.domain.translation.ChatTranslationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** {@code chat_message_translations} 행을 JPA로 읽고 쓰는 내부 엔티티다. */
@Entity
@Table(name = "chat_message_translations")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
class ChatMessageTranslationJpaEntity {

  /** DB가 발급하는 번역 작업 번호. */
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** 원문 {@code chat_messages.id}. 같은 chat 모듈이지만 일관된 no-FK 정책으로 숫자만 참조한다. */
  @Column(nullable = false)
  private Long messageId;

  /** 번역 결과를 받을 수신자 {@code users.id}. */
  @Column(nullable = false)
  private Long recipientUserId;

  /** 원문 저장 시점의 수신자 표시 언어 snapshot. */
  @Column(nullable = false, length = 8)
  private String targetLanguage;

  /** Google 응답에 포함된 감지 원문 언어. */
  @Column(length = 8)
  private String detectedSourceLanguage;

  /** Java 상태 이름을 그대로 저장해 Flyway CHECK와 일치시킨다. */
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private ChatTranslationStatus status;

  /** 성공 시에만 저장되는 번역문. */
  @Column(columnDefinition = "text")
  private String translatedContent;

  /** 번역 제공자 code. */
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private TranslationProvider provider;

  /** 초기 Google 일반 번역 모델 이름. */
  @Column(nullable = false, length = 32)
  private String model;

  /** 외부 API 실제 호출 누적 횟수. */
  @Column(nullable = false)
  private int attemptCount;

  /** PROCESSING 작업의 임시 소유 기한. */
  private Instant leaseUntil;

  /** 원문을 포함하지 않는 마지막 실패 분류 code. */
  @Column(length = 64)
  private String lastFailureCode;

  /** 최종 결과가 확정된 시각. */
  private Instant translatedAt;

  /** 작업 생성 시각. */
  @Column(nullable = false)
  private Instant createdAt;

  /** 마지막 상태 변경 시각. */
  @Column(nullable = false)
  private Instant updatedAt;
}
