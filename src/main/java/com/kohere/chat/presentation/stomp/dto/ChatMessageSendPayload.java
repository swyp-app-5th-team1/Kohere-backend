package com.kohere.chat.presentation.stomp.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** 프런트가 STOMP로 보내는 TEXT 메시지. 방과 발신자는 destination과 인증 Principal에서 결정한다. */
public record ChatMessageSendPayload(@NotNull UUID clientMessageId, @NotBlank String content) {

  public static final int MAX_CONTENT_CODE_POINTS = 3_000;
}
