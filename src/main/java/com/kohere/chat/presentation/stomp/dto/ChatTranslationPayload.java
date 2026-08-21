package com.kohere.chat.presentation.stomp.dto;

import com.kohere.chat.domain.TranslationProvider;
import com.kohere.chat.domain.translation.TranslationResultStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * 받은 TEXT의 번역 작업이 끝났을 때 해당 수신자에게만 전달하는 원문·번역 결합 결과다.
 *
 * <p>공용 room topic으로 원문을 먼저 보내지 않고 이 개인 queue 이벤트 하나에 원문과 최종 번역 상태를 함께 싣는다. 따라서 수신자는 원문 말풍선이 먼저
 * 나타났다가 번역문으로 바뀌는 화면을 만들 필요가 없다. 실패 상태에도 원문은 반드시 포함된다.
 */
public record ChatTranslationPayload(
    /** payload 계약 버전. 현재 값은 1이다. */
    int version,
    /** 이 payload가 MESSAGE_TRANSLATION_UPDATED임을 나타내는 종류. */
    ChatStompEventType eventType,
    /** 번역 결과를 원문 이벤트와 연결하는 서버 메시지 ID. */
    Long messageId,
    /** 프런트가 만든 원래 TEXT UUID. 서버 재전송 결과를 병합할 때 사용할 수 있다. */
    UUID clientMessageId,
    /** 원문 메시지가 속한 채팅방 ID. */
    Long chatRoomId,
    /** 원문을 작성한 사용자 ID. */
    Long senderId,
    /** 사용자가 보낸 수정하지 않은 원문. 번역 성공·실패와 무관하게 항상 존재한다. */
    String originalContent,
    /** 사용자에게 보낼 최종 번역 상태. 내부 PENDING·PROCESSING은 포함하지 않는다. */
    TranslationResultStatus status,
    /** provider가 감지한 원문 언어 code. */
    String sourceLanguage,
    /** 수신자의 표시 언어에서 서버가 결정한 대상 언어 code. */
    String targetLanguage,
    /** SUCCEEDED일 때의 번역문. NOT_REQUIRED 또는 FAILED에는 null이다. */
    String translatedContent,
    /** 번역 결과의 출처와 운영 추적에 사용하는 provider. */
    TranslationProvider provider,
    /** 원문이 MySQL에 저장된 서버 시각. */
    Instant sentAt,
    /** 번역 작업이 최종 상태로 저장된 서버 시각. */
    Instant translatedAt) {}
