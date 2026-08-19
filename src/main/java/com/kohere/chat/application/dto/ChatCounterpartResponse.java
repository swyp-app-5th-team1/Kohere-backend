package com.kohere.chat.application.dto;

/** 현재 로그인 사용자를 기준으로 한 채팅 상대 정보. {@code userId}는 {@code users.id}다. */
public record ChatCounterpartResponse(Long userId, String displayName) {}
