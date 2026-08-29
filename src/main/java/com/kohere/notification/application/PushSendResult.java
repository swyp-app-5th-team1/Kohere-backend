package com.kohere.notification.application;

import java.util.Objects;

/**
 * FCM 토큰 한 개의 발송 결과다.
 *
 * <p>notification listener는 {@link Status#INVALID_TOKEN}만 DB에서 삭제한다. 나머지 실패는 payload·권한·일시 장애일 수
 * 있으므로 토큰을 유지한다.
 *
 * @param fcmToken 결과가 대응하는 요청 토큰
 * @param status provider 응답을 애플리케이션 의미로 변환한 상태
 */
public record PushSendResult(String fcmToken, Status status) {

  /** 결과가 대상 토큰과 상태를 항상 함께 가지도록 생성 시점에 확인한다. */
  public PushSendResult {
    if (fcmToken == null || fcmToken.isBlank()) {
      throw new IllegalArgumentException("fcmToken is required");
    }
    Objects.requireNonNull(status, "status is required");
  }

  /** Firebase 세부 오류를 토큰 정리와 운영 진단에 필요한 최소 상태로 줄인다. */
  public enum Status {
    /** FCM이 요청을 정상 접수했다. */
    SENT,

    /** 앱 설치본이 FCM에서 해지돼 더 이상 사용할 수 없는 토큰이다. */
    INVALID_TOKEN,

    /** provider 내부 오류·할당량·일시 중단처럼 나중에 회복될 수 있는 실패다. */
    TEMPORARY_FAILURE,

    /** APNs 인증키나 Firebase 프로젝트 연결을 점검해야 하는 설정 실패다. */
    CONFIGURATION_FAILURE,

    /** 요청 값 오류 등 토큰 삭제 근거가 없는 비재시도 실패다. */
    PERMANENT_FAILURE,

    /** Firebase 비활성 환경에서 의도적으로 외부 발송을 건너뛰었다. */
    SKIPPED
  }
}
