package com.kohere.chat.application.dto;

import com.kohere.chat.domain.MessageType;
import java.time.Instant;
import java.util.UUID;

/**
 * REST 메시지 이력 응답. {@link MessageType#TEXT}는 원문과 선택적인 번역본을, {@link MessageType#BOOKING_CARD}는 서버가 만든
 * 신청 카드만 포함한다.
 */
public record MessageResponse(
    Long messageId,
    UUID clientMessageId,
    Long chatRoomId,
    Long senderId,
    boolean mine,
    MessageType type,
    String originalContent,
    BookingCardResponse bookingCard,
    MessageTranslationResponse translation,
    Instant sentAt) {}
