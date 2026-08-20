package com.kohere.chat.presentation.stomp.dto;

import java.time.Instant;

/**
 * 채팅방 목록을 다시 조회할 시점을 사용자 개인 queue에 알려 주는 이벤트다.
 *
 * <p>이 이벤트 자체는 방 전체 정보를 담는 정본이 아니다. 연결이 끊겨 놓쳐도 다음 REST 목록 조회로 복구할 수 있으며, {@code ROOM_REOPENED}도 삭제한
 * 과거 대화를 복원했다는 뜻이 아니라 새 활동으로 방이 다시 보이게 됐다는 뜻이다.
 */
public record ChatRoomEventPayload(
    /** payload 계약 버전. 현재 값은 1이다. */
    int version,
    /** ROOM_CREATED, ROOM_UPDATED, ROOM_REOPENED 중 하나. */
    ChatStompEventType eventType,
    /** 변경된 채팅방 식별자. */
    Long roomId,
    /** 이벤트 시점의 마지막 메시지 ID. 빈 방이면 null이다. */
    Long lastMessageId,
    /** 방 목록 변경이 확정된 서버 시각. */
    Instant occurredAt) {}
