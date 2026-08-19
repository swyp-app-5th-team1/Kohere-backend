package com.kohere.chat.presentation.stomp.dto;

import java.util.UUID;

/** PONG 또는 방 구독 준비 완료를 개인 control queue로 전달하는 이벤트. */
public record ChatControlEventPayload(
    int version, ChatStompEventType eventType, UUID requestId, Long roomId, Long highWatermark) {}
