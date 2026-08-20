package com.kohere.chat.application.dto;

import com.kohere.chat.domain.ChatParticipantRole;

/**
 * 채팅방 목록의 한 항목이다.
 *
 * <p>읽음 기능은 이번 범위가 아니므로 {@code unreadCount}를 포함하지 않는다. 목록은 사용자에게 현재 보이는 방만 반환하며, 삭제로 숨긴 방의 내부 상태나
 * 복구 가능 여부도 노출하지 않는다. 빈 방이면 {@code lastMessage} 자체가 {@code null}이다.
 */
public record ChatRoomResponse(
    /** 서버가 발급한 채팅방 식별자. */
    Long chatRoomId,
    /** 현재 사용자의 방 안 역할. */
    ChatParticipantRole myRole,
    /** 목록에 표시할 매물 요약 사본. */
    ChatListingSummaryResponse listing,
    /** 현재 사용자 기준의 상대 참여자. */
    ChatCounterpartResponse counterpart,
    /** 사용자에게 보이는 마지막 메시지 요약. 메시지가 없으면 null. */
    ChatLastMessageResponse lastMessage,
    /** 어느 방향이든 차단 관계가 있어 새 채팅을 보낼 수 없으면 true. */
    boolean blocked) {}
