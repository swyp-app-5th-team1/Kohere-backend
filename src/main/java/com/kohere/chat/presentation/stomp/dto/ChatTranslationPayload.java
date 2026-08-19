package com.kohere.chat.presentation.stomp.dto;

import com.kohere.chat.domain.TranslationProvider;
import java.time.Instant;

/** 받은 TEXT의 번역 처리가 끝났을 때 해당 수신자에게만 전달하는 최종 결과. */
public record ChatTranslationPayload(
    int version,
    ChatStompEventType eventType,
    Long messageId,
    Long chatRoomId,
    ChatTranslationResultStatus status,
    String sourceLanguage,
    String targetLanguage,
    String translatedContent,
    TranslationProvider provider,
    Instant translatedAt) {}
