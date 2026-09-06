package com.kohere.notification.presentation.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 로그인 사용자가 변경할 채팅 푸시 수신값이다.
 *
 * @param chatPushEnabled 모든 채팅 푸시를 받을지 여부
 */
public record NotificationPreferenceUpdateRequest(
    @NotNull(message = "{validation.required}") Boolean chatPushEnabled) {}
