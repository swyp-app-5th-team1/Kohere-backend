package com.kohere.chat.presentation.stomp.dto;

import java.util.UUID;

/**
 * PONG 또는 방 구독 준비 완료를 개인 control queue로 전달하는 이벤트다.
 *
 * <p>단순히 SUBSCRIBE frame을 보낸 시점에는 broker 등록이 끝났다고 보장할 수 없다. 서버가 {@code SUBSCRIPTION_READY}와 DB
 * high-watermark를 보낸 뒤 프런트엔드가 REST 누락 조회를 실행하게 해 그 사이의 메시지를 메운다.
 */
public record ChatControlEventPayload(
    /** payload 호환성을 판단하는 계약 버전. 현재 값은 1이다. */
    int version,
    /** PONG 또는 SUBSCRIPTION_READY를 구분하는 이벤트 종류. */
    ChatStompEventType eventType,
    /** PONG을 원래 ping과 연결하는 ID. SUBSCRIPTION_READY에는 null일 수 있다. */
    UUID requestId,
    /** 준비가 끝난 채팅방 ID. PONG처럼 특정 방과 무관한 이벤트에는 null일 수 있다. */
    Long roomId,
    /** 구독 준비 시점에 DB에 저장된 마지막 메시지 ID. 빈 방이거나 PONG이면 null이다. */
    Long highWatermark) {}
