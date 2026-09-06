package com.kohere.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.kohere.notification.domain.NotificationPreference;
import com.kohere.notification.domain.NotificationPreferenceRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 알림 설정 서비스의 미설정 기본값과 명시적인 true/false 저장 규칙을 검증한다. */
@ExtendWith(MockitoExtension.class)
class NotificationPreferenceServiceTest {

  private static final long USER_ID = 7L;

  @Mock private NotificationPreferenceRepository preferenceRepository;

  /** 설정 행이 없는 신규·기존 사용자는 DB를 변경하지 않고 true를 받는다. */
  @Test
  void missingPreferenceDefaultsToEnabledWithoutInsert() {
    given(preferenceRepository.findByUserId(USER_ID)).willReturn(Optional.empty());
    NotificationPreferenceService service = service();

    boolean enabled = service.isChatPushEnabled(USER_ID);

    assertThat(enabled).isTrue();
    verify(preferenceRepository, never()).upsert(any(), any());
  }

  /** 설정 행이 있으면 기본값 대신 사용자가 마지막으로 저장한 false를 반환한다. */
  @Test
  void returnsStoredPreference() {
    given(preferenceRepository.findByUserId(USER_ID))
        .willReturn(Optional.of(new NotificationPreference(USER_ID, false)));
    NotificationPreferenceService service = service();

    assertThat(service.isChatPushEnabled(USER_ID)).isFalse();
  }

  /** 변경 요청의 사용자와 false를 upsert하고 같은 값을 API 응답용 결과로 반환한다. */
  @Test
  void updatesExplicitPreference() {
    NotificationPreferenceService service = service();

    boolean updated = service.updateChatPushEnabled(USER_ID, false);

    ArgumentCaptor<NotificationPreference> preference =
        ArgumentCaptor.forClass(NotificationPreference.class);
    verify(preferenceRepository).upsert(preference.capture(), any(Instant.class));
    assertThat(preference.getValue().userId()).isEqualTo(USER_ID);
    assertThat(preference.getValue().chatPushEnabled()).isFalse();
    assertThat(updated).isFalse();
  }

  /** 조회 입력도 도메인 식별자 조건을 지켜 잘못된 내부 호출을 조기에 발견한다. */
  @Test
  void rejectsInvalidUserIdBeforeRepositoryCall() {
    NotificationPreferenceService service = service();

    assertThatThrownBy(() -> service.isChatPushEnabled(0L))
        .isInstanceOf(IllegalArgumentException.class);

    verify(preferenceRepository, never()).findByUserId(eq(0L));
  }

  /** 각 테스트가 공유할 mock repository 기반 서비스를 만든다. */
  private NotificationPreferenceService service() {
    return new NotificationPreferenceService(preferenceRepository);
  }
}
