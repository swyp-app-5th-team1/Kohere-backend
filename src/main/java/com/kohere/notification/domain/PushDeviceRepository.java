package com.kohere.notification.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 앱 설치본과 FCM 토큰을 저장·조회하는 Notification 모듈의 영속 포트다. */
public interface PushDeviceRepository {

  /** 신규 설치본을 저장하거나 기존 설치본의 사용자·토큰·확인 시각을 갱신한다. */
  PushDevice save(PushDevice device);

  /** 동일 설치본 등록을 원자적으로 갱신할 수 있도록 installation 행을 쓰기 잠금으로 조회한다. */
  Optional<PushDevice> findByInstallationIdForUpdate(UUID installationId);

  /** 같은 FCM 발송 주소가 이미 다른 행에 연결됐는지 확인한다. */
  Optional<PushDevice> findByFcmToken(String fcmToken);

  /** 같은 FCM 토큰을 다른 installation으로 옮길 때 중복 등록을 막도록 현재 소유 행을 쓰기 잠금으로 조회한다. */
  Optional<PushDevice> findByFcmTokenForUpdate(String fcmToken);

  /** 채팅 수신자의 모든 등록 기기를 안정적인 내부 ID 순서로 조회한다. */
  List<PushDevice> findAllByUserId(long userId);

  /** 로그아웃한 사용자의 현재 installation만 삭제하고 실제 삭제 여부를 반환한다. */
  boolean deleteByInstallationIdAndUserId(UUID installationId, long userId);

  /** FCM이 영구 무효라고 확인한 토큰의 행을 삭제하고 실제 삭제 여부를 반환한다. */
  boolean deleteByFcmToken(String fcmToken);

  /** 회원 탈퇴 시 사용자의 모든 installation을 삭제하고 삭제 개수를 반환한다. */
  long deleteAllByUserId(long userId);
}
