package com.kohere.chat.presentation.stomp.dto;

import java.time.Instant;

/** 채팅방 목록을 갱신하라고 사용자 개인 queue에 알리는 이벤트. 정본 데이터는 REST 목록이다. */
public record ChatRoomEventPayload(
    int version,
    ChatStompEventType eventType,
    Long roomId,
    Long lastMessageId,
    Instant occurredAt) {}
