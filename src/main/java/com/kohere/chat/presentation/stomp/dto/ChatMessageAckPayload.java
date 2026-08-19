package com.kohere.chat.presentation.stomp.dto;

import java.time.Instant;
import java.util.UUID;

/** 원래 TEXT SEND를 보낸 session에만 전달하는 애플리케이션 수준의 DB 저장 결과. */
public record ChatMessageAckPayload(
    int version, UUID clientMessageId, Long messageId, Instant sentAt, boolean duplicate) {}
