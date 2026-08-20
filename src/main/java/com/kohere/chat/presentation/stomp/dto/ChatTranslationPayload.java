package com.kohere.chat.presentation.stomp.dto;

import com.kohere.chat.domain.TranslationProvider;
import java.time.Instant;

/**
 * 받은 TEXT의 번역 작업이 끝났을 때 해당 수신자에게만 전달하는 최종 결과다.
 *
 * <p>원문 이벤트와 destination을 분리한 이유는 번역 대상 언어와 결과가 사용자별로 다르기 때문이다. 서로 다른 queue의 도착 순서는 보장되지 않으므로
 * 프런트엔드는 {@code messageId}로 원문과 합치며, 번역 실패에도 이미 저장된 원문은 그대로 유지한다.
 */
public record ChatTranslationPayload(
    /** payload 계약 버전. 현재 값은 1이다. */
    int version,
    /** 이 payload가 MESSAGE_TRANSLATION_UPDATED임을 나타내는 종류. */
    ChatStompEventType eventType,
    /** 번역 결과를 원문 이벤트와 연결하는 서버 메시지 ID. */
    Long messageId,
    /** 원문 메시지가 속한 채팅방 ID. */
    Long chatRoomId,
    /** 사용자에게 보낼 최종 번역 상태. 내부 PENDING·PROCESSING은 포함하지 않는다. */
    ChatTranslationResultStatus status,
    /** provider가 감지한 원문 언어 code. */
    String sourceLanguage,
    /** 수신자의 표시 언어에서 서버가 결정한 대상 언어 code. */
    String targetLanguage,
    /** SUCCEEDED일 때의 번역문. NOT_REQUIRED 또는 FAILED에는 null이다. */
    String translatedContent,
    /** 번역 결과의 출처와 운영 추적에 사용하는 provider. */
    TranslationProvider provider,
    /** 번역 작업이 최종 상태로 저장된 서버 시각. */
    Instant translatedAt) {}
