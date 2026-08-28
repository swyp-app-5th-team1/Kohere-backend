package com.kohere.notification.presentation.dto;

import com.kohere.notification.domain.PushDevice;
import com.kohere.notification.domain.PushPlatform;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 현재 앱 설치본에 연결할 FCM 토큰과 플랫폼을 받는 등록 요청이다.
 *
 * @param fcmToken Firebase가 앱에 발급한 현재 토큰
 * @param platform 토큰을 발급받은 앱 플랫폼
 */
public record PushDeviceRegisterRequest(
    @NotBlank @Size(max = PushDevice.MAX_FCM_TOKEN_LENGTH) String fcmToken,
    @NotNull PushPlatform platform) {}
