package com.kohere.notification.application;

import com.kohere.notification.domain.PushDevice;
import com.kohere.notification.domain.PushDeviceRepository;
import com.kohere.notification.domain.PushPlatform;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 로그인한 사용자의 앱 설치본과 현재 FCM 토큰을 등록하고 로그아웃한 설치본을 삭제하는 응용 서비스다. */
@Service
@RequiredArgsConstructor
public class PushDeviceService {

  private final PushDeviceRepository pushDeviceRepository;

  /**
   * 현재 앱 설치본의 FCM 토큰을 등록하거나 갱신한다.
   *
   * <p>같은 installation은 새 행을 만들지 않고 사용자·토큰·마지막 확인 시각을 갱신한다. 같은 토큰이 다른 installation에 남아 있으면 현재 요청을
   * 최신 정보로 보고 오래된 연결을 먼저 제거한다.
   *
   * @param userId 현재 로그인한 사용자 ID
   * @param installationId 앱이 생성하고 보관하는 설치본 UUID
   * @param fcmToken Firebase가 현재 발급한 FCM 토큰
   * @param platform 토큰을 발급받은 앱 플랫폼
   */
  @Transactional
  public void register(long userId, UUID installationId, String fcmToken, PushPlatform platform) {
    Instant now = Instant.now();
    Optional<PushDevice> installation =
        pushDeviceRepository.findByInstallationIdForUpdate(installationId);
    Optional<PushDevice> tokenOwner = pushDeviceRepository.findByFcmTokenForUpdate(fcmToken);

    // 앱 재설치 등으로 같은 토큰이 다른 installation에 연결돼 있으면 중복 발송 전에 오래된 행을 제거한다.
    if (belongsToAnotherInstallation(tokenOwner, installationId)) {
      pushDeviceRepository.deleteByFcmToken(fcmToken);
    }

    PushDevice currentDevice =
        installation
            .map(device -> device.refreshRegistration(userId, fcmToken, platform, now))
            .orElseGet(() -> PushDevice.register(userId, installationId, fcmToken, platform, now));
    pushDeviceRepository.save(currentDevice);
  }

  /**
   * 로그아웃한 현재 사용자의 앱 설치본을 삭제한다.
   *
   * <p>이미 없거나 다른 사용자가 소유한 installation이면 아무 것도 지우지 않고 성공해 로그아웃 재시도를 안전하게 만든다.
   *
   * @param userId 현재 로그인한 사용자 ID
   * @param installationId 삭제할 앱 설치본 UUID
   */
  @Transactional
  public void delete(long userId, UUID installationId) {
    pushDeviceRepository.deleteByInstallationIdAndUserId(installationId, userId);
  }

  /** 조회한 토큰이 현재 등록하려는 installation이 아닌 오래된 행에 속하는지 판단한다. */
  private boolean belongsToAnotherInstallation(
      Optional<PushDevice> tokenOwner, UUID currentInstallationId) {
    return tokenOwner
        .map(device -> !device.getInstallationId().equals(currentInstallationId))
        .orElse(false);
  }
}
