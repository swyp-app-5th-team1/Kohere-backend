package com.kohere.chat.presentation.stomp.dto;

import java.util.UUID;

/** 개별 TEXT SEND가 거부됐을 때 원래 발신 session에 전달하는 오류. */
public record ChatMessageErrorPayload(
    int version, UUID clientMessageId, String code, String message) {}
