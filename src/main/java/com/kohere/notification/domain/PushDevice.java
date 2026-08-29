package com.kohere.notification.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;

/**
 * 로그인 사용자와 한 앱 설치본의 FCM 발송 주소를 연결하는 순수 도메인 모델이다.
 *
 * <p>{@code installationId}는 기기 하드웨어 주소가 아니라 앱이 생성한 설치본 UUID다. FCM 토큰이 바뀌어도 같은 UUID의 행을 갱신해 오래된 토큰이
 * 새 행으로 계속 쌓이지 않게 한다.
 */
@Getter
@Builder(toBuilder = true)
public class PushDevice {

  /** DB가 발급하는 내부 ID이며 신규 등록 전에는 null이다. */
  private final Long id;

  /** 현재 이 설치본으로 로그인한 {@code users.id}다. */
  private final Long userId;

  /** 앱이 한 설치본을 구분하려고 생성·보관하는 UUID다. */
  private final UUID installationId;

  /** Firebase가 발급하고 백엔드가 실제 발송 대상으로 사용하는 opaque 토큰이다. */
  private final String fcmToken;

  /** 토큰을 발급한 플랫폼이며 현재 허용값은 {@link PushPlatform#IOS}다. */
  private final PushPlatform platform;

  /** 앱이 이 설치본의 현재 토큰을 마지막으로 등록·갱신한 UTC 시각이다. */
  private final Instant lastSeenAt;

  /** 이 설치본이 서버에 처음 등록된 UTC 시각이다. */
  private final Instant createdAt;

  /** 사용자·토큰·마지막 확인 시각 중 하나를 마지막으로 변경한 UTC 시각이다. */
  private final Instant updatedAt;

  /** DB와 요청 검증이 공유할 FCM 토큰 최대 길이다. 토큰은 자르거나 정규화하지 않는다. */
  public static final int MAX_FCM_TOKEN_LENGTH = 1024;

  /**
   * 처음 보는 앱 설치본을 신규 등록 상태로 만든다.
   *
   * @param userId 현재 로그인한 사용자 ID
   * @param installationId 앱 설치본 UUID
   * @param fcmToken Firebase가 발급한 현재 토큰
   * @param platform 토큰 발급 플랫폼
   * @param now 서버가 결정한 등록 UTC 시각
   * @return DB ID만 비어 있고 세 시각이 같은 신규 기기
   */
  public static PushDevice register(
      long userId, UUID installationId, String fcmToken, PushPlatform platform, Instant now) {
    validateRegistration(userId, installationId, fcmToken, platform, now);
    return PushDevice.builder()
        .userId(userId)
        .installationId(installationId)
        .fcmToken(fcmToken)
        .platform(platform)
        .lastSeenAt(now)
        .createdAt(now)
        .updatedAt(now)
        .build();
  }

  /**
   * 같은 설치본의 현재 사용자와 FCM 토큰을 새 등록 정보로 교체한다.
   *
   * <p>로그인 계정이 바뀌면 {@code userId}를, Firebase가 토큰을 회전하면 {@code fcmToken}을 갱신한다. 설치본 ID와 최초 생성 시각은
   * 유지한다.
   *
   * @param currentUserId 지금 로그인한 사용자 ID
   * @param currentFcmToken Firebase가 현재 알려 준 토큰
   * @param currentPlatform 현재 앱 플랫폼
   * @param now 서버가 결정한 갱신 UTC 시각
   * @return 같은 설치본 ID와 최초 생성 시각을 유지한 갱신 결과
   */
  public PushDevice refreshRegistration(
      long currentUserId, String currentFcmToken, PushPlatform currentPlatform, Instant now) {
    validateRegistration(currentUserId, installationId, currentFcmToken, currentPlatform, now);
    Instant originalCreatedAt = Objects.requireNonNull(createdAt, "createdAt is required");
    if (now.isBefore(originalCreatedAt)) {
      throw new IllegalArgumentException("updated time cannot be before created time");
    }
    return toBuilder()
        .userId(currentUserId)
        .fcmToken(currentFcmToken)
        .platform(currentPlatform)
        .lastSeenAt(now)
        .updatedAt(now)
        .build();
  }

  /** 신규·갱신 양쪽에서 사용자, 설치본, 토큰, 플랫폼과 서버 시각의 필수 조건을 검증한다. */
  private static void validateRegistration(
      long userId, UUID installationId, String fcmToken, PushPlatform platform, Instant now) {
    if (userId < 1) {
      throw new IllegalArgumentException("userId must be positive");
    }
    Objects.requireNonNull(installationId, "installationId is required");
    Objects.requireNonNull(platform, "platform is required");
    Objects.requireNonNull(now, "registration time is required");
    if (fcmToken == null || fcmToken.isBlank()) {
      throw new IllegalArgumentException("fcmToken is required");
    }
    if (fcmToken.length() > MAX_FCM_TOKEN_LENGTH) {
      throw new IllegalArgumentException("fcmToken is too long");
    }
  }
}
