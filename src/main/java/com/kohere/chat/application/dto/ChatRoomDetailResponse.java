package com.kohere.chat.application.dto;

import com.kohere.chat.domain.ChatParticipantRole;

/** 채팅방 화면의 헤더와 역할 정보를 반환하는 단건 응답. */
public record ChatRoomDetailResponse(
    Long chatRoomId,
    ChatParticipantRole myRole,
    ChatListingSummaryResponse listing,
    ChatCounterpartResponse counterpart,
    boolean blocked) {}
