package com.kohere.notification.application;

import com.kohere.notification.domain.NotificationPreferenceRepository;
import com.kohere.notification.domain.PushDeviceRepository;
import com.kohere.user.api.UserWithdrawnEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 회원 탈퇴 이벤트를 받아 Notification 모듈이 소유한 기기 주소와 수신 설정을 함께 정리한다. */
@Component
@RequiredArgsConstructor
public class NotificationUserWithdrawnEventListener {

  private final PushDeviceRepository pushDeviceRepository;
  private final NotificationPreferenceRepository preferenceRepository;

  /**
   * 탈퇴 트랜잭션 안에서 사용자 푸시 데이터를 동기 삭제한다.
   *
   * <p>두 삭제는 모두 멱등하며 실패하면 탈퇴 트랜잭션도 롤백돼 사용자 상태와 알림 데이터가 어긋나지 않는다.
   *
   * @param event 탈퇴한 사용자 식별자를 담은 공개 이벤트
   */
  @EventListener
  public void onUserWithdrawn(UserWithdrawnEvent event) {
    pushDeviceRepository.deleteAllByUserId(event.userId());
    preferenceRepository.deleteByUserId(event.userId());
  }
}
