package com.kohere.chat.application;

import com.kohere.chat.domain.Message;

/**
 * TEXT 저장 트랜잭션이 확정한 결과다.
 *
 * <p>STOMP 계층은 이 결과로 발신자 ACK와 방 목록 갱신 신호를 보낸다. 신규 TEXT의 원문은 수신자에게 먼저 발행하지 않고, {@code
 * translationId}의 Worker가 끝난 뒤 번역 결과와 함께 전달한다. 중복 재시도는 ACK만 다시 보낸다.
 */
public record TextMessageSaveResult(
    /** MySQL이 발급한 최종 messageId와 최초 저장 시각을 가진 TEXT 정본. */
    Message message,
    /** 같은 UUID·같은 본문이 이미 저장돼 기존 정본을 반환한 경우 true. */
    boolean duplicate,
    /** 이 1:1 채팅의 상대 사용자 ID. */
    long recipientUserId,
    /** 숨겼던 수신자 채팅방이 실제 신규 메시지로 다시 표시된 경우 true. */
    boolean recipientRoomReopened,
    /** 신규 원문과 함께 저장된 수신자용 번역 작업 ID. 중복 재시도에는 null이다. */
    Long translationId) {}
