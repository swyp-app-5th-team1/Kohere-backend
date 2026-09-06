package com.kohere.notification.presentation.dto;

/**
 * 현재 계정에 적용되는 채팅 푸시 수신 설정 응답이다.
 *
 * @param chatPushEnabled 모든 채팅 푸시를 받을지 여부
 */
public record NotificationPreferenceResponse(boolean chatPushEnabled) {}
