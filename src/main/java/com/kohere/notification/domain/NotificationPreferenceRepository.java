package com.kohere.notification.domain;

import java.time.Instant;
import java.util.Optional;

/** 사용자별 알림 수신 설정을 저장하고 조회하는 Notification 모듈의 영속 포트다. */
public interface NotificationPreferenceRepository {

  /** 설정을 한 번이라도 저장한 사용자의 현재 값을 조회하며, 미설정 사용자는 빈 값을 반환한다. */
  Optional<NotificationPreference> findByUserId(long userId);

  /** 사용자 행이 없으면 만들고 있으면 채팅 푸시 값과 수정 시각만 원자적으로 갱신한다. */
  void upsert(NotificationPreference preference, Instant changedAt);

  /** 회원 탈퇴 시 해당 사용자의 설정 행을 삭제하고 실제 삭제 개수를 반환한다. */
  long deleteByUserId(long userId);
}
