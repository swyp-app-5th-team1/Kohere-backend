package com.kohere.chat.application.dto;

import com.kohere.chat.domain.MessageType;
import java.time.Instant;

/** 채팅방 목록의 마지막 메시지 요약. 빈 방이면 이 객체 자체가 null이다. */
public record ChatLastMessageResponse(
    Long messageId, MessageType type, String preview, Instant sentAt) {}
