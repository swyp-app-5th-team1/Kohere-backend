package com.kohere.chat.presentation.stomp.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 원래 TEXT SEND를 보낸 session에만 전달하는 애플리케이션 수준의 DB 저장 결과다.
 *
 * <p>STOMP protocol ACK가 아니라 MySQL commit 결과다. 프런트엔드는 {@code clientMessageId}로 임시 말풍선을 찾아 서버 {@code
 * messageId}와 합친다. topic 이벤트와 도착 순서는 보장하지 않는다.
 */
public record ChatMessageAckPayload(
    /** payload 계약 버전. 현재 값은 1이다. */
    int version,
    /** 프런트엔드가 원래 SEND와 임시 말풍선에 함께 붙인 UUID. */
    UUID clientMessageId,
    /** MySQL에 저장된 최종 메시지 ID. 중복 재시도에도 기존 값이 돌아온다. */
    Long messageId,
    /** 최초 메시지가 저장된 서버 시각. 중복 재시도 시 새 시각을 만들지 않는다. */
    Instant sentAt,
    /** 같은 UUID·같은 본문이 이미 저장돼 기존 결과를 돌려준 경우 true. */
    boolean duplicate) {}
