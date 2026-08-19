package com.kohere.chat.application.dto;

import com.kohere.chat.domain.ChatParticipantRole;

/** 채팅방 목록 항목. 매물·상대·현재 사용자 역할과 사용자에게 보이는 마지막 메시지 요약을 반환한다. 빈 방이면 {@code lastMessage}가 null이다. */
public record ChatRoomResponse(
    Long chatRoomId,
    ChatParticipantRole myRole,
    ChatListingSummaryResponse listing,
    ChatCounterpartResponse counterpart,
    ChatLastMessageResponse lastMessage,
    boolean blocked) {}
