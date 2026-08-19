package com.kohere.chat.application.dto;

/** 채팅방 목록과 헤더에 표시할 매물 요약. 방 생성 시 저장한 표시용 사본을 사용한다. */
public record ChatListingSummaryResponse(
    String listingId, String title, String thumbnailUrl, String address) {}
