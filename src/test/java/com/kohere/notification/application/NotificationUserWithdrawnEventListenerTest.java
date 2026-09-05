package com.kohere.notification.application;

import static org.mockito.Mockito.inOrder;

import com.kohere.notification.domain.NotificationPreferenceRepository;
import com.kohere.notification.domain.PushDeviceRepository;
import com.kohere.user.api.UserWithdrawnEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 회원 탈퇴 시 Notification 모듈이 소유한 사용자 데이터를 모두 정리하는지 검증한다. */
@ExtendWith(MockitoExtension.class)
class NotificationUserWithdrawnEventListenerTest {

  @Mock private PushDeviceRepository pushDeviceRepository;
  @Mock private NotificationPreferenceRepository preferenceRepository;

  /** 탈퇴 사용자 ID로 모든 기기와 알림 설정을 같은 listener에서 삭제한다. */
  @Test
  void deletesPushDevicesAndPreference() {
    NotificationUserWithdrawnEventListener listener =
        new NotificationUserWithdrawnEventListener(pushDeviceRepository, preferenceRepository);

    listener.onUserWithdrawn(new UserWithdrawnEvent(7L));

    InOrder order = inOrder(pushDeviceRepository, preferenceRepository);
    order.verify(pushDeviceRepository).deleteAllByUserId(7L);
    order.verify(preferenceRepository).deleteByUserId(7L);
  }
}
