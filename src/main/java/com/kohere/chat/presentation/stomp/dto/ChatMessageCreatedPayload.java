package com.kohere.chat.presentation.stomp.dto;

import com.kohere.chat.application.dto.BookingCardResponse;
import com.kohere.chat.domain.MessageType;
import java.time.Instant;
import java.util.UUID;

/** 저장 완료 뒤 방 topic으로 전달하는 TEXT 또는 BOOKING_CARD 이벤트. */
public record ChatMessageCreatedPayload(
    int version,
    ChatStompEventType eventType,
    Long messageId,
    UUID clientMessageId,
    Long chatRoomId,
    Long senderId,
    MessageType type,
    String originalContent,
    BookingCardResponse bookingCard,
    Instant sentAt) {}
