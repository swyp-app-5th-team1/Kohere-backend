package com.kohere.chat.application;

import com.kohere.chat.domain.Message;

/**
 * TEXT 저장 트랜잭션이 확정한 결과다.
 *
 * <p>STOMP 계층은 이 결과만 보고 신규 메시지는 room topic으로 발행하고, 중복 재시도는 ACK만 다시 보낸다. 수신자의 방이 이번 메시지로 다시 표시됐는지도
 * 함께 알려 개인 {@code ROOM_REOPENED} 이벤트를 필요한 경우에만 만들 수 있게 한다.
 */
public record TextMessageSaveResult(
    /** MySQL이 발급한 최종 messageId와 최초 저장 시각을 가진 TEXT 정본. */
    Message message,
    /** 같은 UUID·같은 본문이 이미 저장돼 기존 정본을 반환한 경우 true. */
    boolean duplicate,
    /** 이 1:1 채팅의 상대 사용자 ID. */
    long recipientUserId,
    /** 숨겼던 수신자 채팅방이 실제 신규 메시지로 다시 표시된 경우 true. */
    boolean recipientRoomReopened) {}
