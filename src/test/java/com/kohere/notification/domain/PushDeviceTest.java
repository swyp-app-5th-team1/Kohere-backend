package com.kohere.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Spring 컨텍스트 없이 앱 설치본 등록과 FCM 토큰 갱신 불변식을 검증한다. */
class PushDeviceTest {

  private static final UUID INSTALLATION_ID =
      UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
  private static final Instant REGISTERED_AT = Instant.parse("2026-08-29T06:30:00Z");

  /** 최초 등록은 DB ID만 비우고 사용자·설치본·토큰과 세 시각을 같은 값으로 고정하는지 확인한다. */
  @Test
  void registerCreatesNewInstallation() {
    PushDevice device =
        PushDevice.register(10L, INSTALLATION_ID, "fcm-token-v1", PushPlatform.IOS, REGISTERED_AT);

    assertThat(device.getId()).isNull();
    assertThat(device.getUserId()).isEqualTo(10L);
    assertThat(device.getInstallationId()).isEqualTo(INSTALLATION_ID);
    assertThat(device.getFcmToken()).isEqualTo("fcm-token-v1");
    assertThat(device.getPlatform()).isEqualTo(PushPlatform.IOS);
    assertThat(device.getLastSeenAt()).isEqualTo(REGISTERED_AT);
    assertThat(device.getCreatedAt()).isEqualTo(REGISTERED_AT);
    assertThat(device.getUpdatedAt()).isEqualTo(REGISTERED_AT);
  }

  /** 토큰 회전이나 계정 변경에는 같은 installation과 최초 생성 시각을 유지하면서 현재 값만 교체하는지 확인한다. */
  @Test
  void refreshRegistrationUpdatesCurrentOwnerAndToken() {
    PushDevice registered =
        PushDevice.builder()
            .id(1L)
            .userId(10L)
            .installationId(INSTALLATION_ID)
            .fcmToken("fcm-token-v1")
            .platform(PushPlatform.IOS)
            .lastSeenAt(REGISTERED_AT)
            .createdAt(REGISTERED_AT)
            .updatedAt(REGISTERED_AT)
            .build();
    Instant refreshedAt = REGISTERED_AT.plusSeconds(300);

    PushDevice refreshed =
        registered.refreshRegistration(20L, "fcm-token-v2", PushPlatform.IOS, refreshedAt);

    assertThat(refreshed.getId()).isEqualTo(1L);
    assertThat(refreshed.getInstallationId()).isEqualTo(INSTALLATION_ID);
    assertThat(refreshed.getCreatedAt()).isEqualTo(REGISTERED_AT);
    assertThat(refreshed.getUserId()).isEqualTo(20L);
    assertThat(refreshed.getFcmToken()).isEqualTo("fcm-token-v2");
    assertThat(refreshed.getLastSeenAt()).isEqualTo(refreshedAt);
    assertThat(refreshed.getUpdatedAt()).isEqualTo(refreshedAt);
  }

  /** 사용자 ID·설치본·토큰·플랫폼·시각의 필수 조건을 위반한 신규 등록을 도메인에서 거부하는지 확인한다. */
  @Test
  void registerRejectsInvalidRequiredValues() {
    assertThatThrownBy(
            () ->
                PushDevice.register(
                    0L, INSTALLATION_ID, "fcm-token", PushPlatform.IOS, REGISTERED_AT))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> PushDevice.register(10L, null, "fcm-token", PushPlatform.IOS, REGISTERED_AT))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () -> PushDevice.register(10L, INSTALLATION_ID, " ", PushPlatform.IOS, REGISTERED_AT))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> PushDevice.register(10L, INSTALLATION_ID, "fcm-token", null, REGISTERED_AT))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () -> PushDevice.register(10L, INSTALLATION_ID, "fcm-token", PushPlatform.IOS, null))
        .isInstanceOf(NullPointerException.class);
  }

  /** DB 컬럼 상한을 넘는 FCM 토큰을 잘라 저장하지 않고 명확하게 거부하는지 확인한다. */
  @Test
  void registerRejectsTooLongFcmToken() {
    String tooLongToken = "a".repeat(PushDevice.MAX_FCM_TOKEN_LENGTH + 1);

    assertThatThrownBy(
            () ->
                PushDevice.register(
                    10L, INSTALLATION_ID, tooLongToken, PushPlatform.IOS, REGISTERED_AT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("fcmToken is too long");
  }

  /** 최초 등록보다 과거 시각으로 갱신해 DB 시각 순서를 깨뜨리는 요청을 거부하는지 확인한다. */
  @Test
  void refreshRegistrationRejectsTimeBeforeCreation() {
    PushDevice registered =
        PushDevice.register(10L, INSTALLATION_ID, "fcm-token-v1", PushPlatform.IOS, REGISTERED_AT);

    assertThatThrownBy(
            () ->
                registered.refreshRegistration(
                    10L, "fcm-token-v2", PushPlatform.IOS, REGISTERED_AT.minusSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("updated time cannot be before created time");
  }
}
