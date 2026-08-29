package com.kohere.chat;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 새 채팅 메시지 정본이 MySQL에 저장됐음을 다른 모듈에 알리는 chat 모듈의 공개 이벤트다.
 *
 * <p>notification 모듈은 {@code messageType}으로 알림 문구를 고르고 {@code recipientUserId}의 등록 기기로 FCM을 발송한다.
 * 메시지 원문과 카드 상세 payload는 외부 푸시 제공자에 전달하지 않으므로 이벤트에도 포함하지 않는다.
 *
 * @param eventId 동일 이벤트 처리와 로그를 연결하는 UUID
 * @param messageType 푸시 원인이 된 채팅 메시지 종류
 * @param roomId 메시지가 저장된 채팅방 ID
 * @param messageId MySQL이 발급한 채팅 메시지 ID
 * @param recipientUserId 푸시를 받을 사용자 ID
 * @param listingId 채팅방이 속한 매물 ID
 * @param listingTitle 채팅방 생성 시점에 고정한 매물 제목
 * @param sentAt 원인 메시지가 MySQL에 저장된 UTC 시각
 */
public record ChatMessageCreatedEvent(
    UUID eventId,
    ChatMessageKind messageType,
    long roomId,
    long messageId,
    long recipientUserId,
    String listingId,
    String listingTitle,
    Instant sentAt) {

  /** 잘못된 식별자나 빈 표시값이 모듈 이벤트로 영구 기록되지 않도록 생성 시점에 검증한다. */
  public ChatMessageCreatedEvent {
    Objects.requireNonNull(eventId, "eventId is required");
    Objects.requireNonNull(messageType, "messageType is required");
    Objects.requireNonNull(sentAt, "sentAt is required");
    if (roomId < 1 || messageId < 1 || recipientUserId < 1) {
      throw new IllegalArgumentException("roomId, messageId and recipientUserId must be positive");
    }
    if (listingId == null || listingId.isBlank()) {
      throw new IllegalArgumentException("listingId is required");
    }
    if (listingTitle == null || listingTitle.isBlank()) {
      throw new IllegalArgumentException("listingTitle is required");
    }
  }
}
