package com.kohere.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.kohere.notification.domain.PushDevice;
import com.kohere.notification.domain.PushDeviceRepository;
import com.kohere.notification.domain.PushPlatform;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** {@link PushDeviceService}의 설치본 갱신, 토큰 중복 정리와 사용자 범위 삭제 규칙을 검증한다. */
@ExtendWith(MockitoExtension.class)
class PushDeviceServiceTest {

  private static final long USER_ID = 7L;
  private static final UUID INSTALLATION_ID =
      UUID.fromString("e7714046-1634-4dc1-a97e-8c1f91a72483");

  @Mock private PushDeviceRepository pushDeviceRepository;

  /** 처음 보는 installation이면 현재 사용자와 토큰으로 신규 기기를 저장하는지 확인한다. */
  @Test
  void registerCreatesDeviceForNewInstallation() {
    PushDeviceService service = new PushDeviceService(pushDeviceRepository);
    given(pushDeviceRepository.findByInstallationIdForUpdate(INSTALLATION_ID))
        .willReturn(Optional.empty());
    given(pushDeviceRepository.findByFcmTokenForUpdate("new-token")).willReturn(Optional.empty());

    service.register(USER_ID, INSTALLATION_ID, "new-token", PushPlatform.IOS);

    ArgumentCaptor<PushDevice> savedDevice = ArgumentCaptor.forClass(PushDevice.class);
    verify(pushDeviceRepository).save(savedDevice.capture());
    assertThat(savedDevice.getValue().getUserId()).isEqualTo(USER_ID);
    assertThat(savedDevice.getValue().getInstallationId()).isEqualTo(INSTALLATION_ID);
    assertThat(savedDevice.getValue().getFcmToken()).isEqualTo("new-token");
    assertThat(savedDevice.getValue().getPlatform()).isEqualTo(PushPlatform.IOS);
    assertThat(savedDevice.getValue().getCreatedAt()).isNotNull();
    assertThat(savedDevice.getValue().getLastSeenAt())
        .isEqualTo(savedDevice.getValue().getCreatedAt());
    verify(pushDeviceRepository, never()).deleteByFcmToken(any());
  }

  /** 같은 installation이 다시 등록되면 최초 시각은 유지하고 사용자·토큰·마지막 확인 시각을 갱신하는지 확인한다. */
  @Test
  void registerRefreshesExistingInstallation() {
    Instant firstRegisteredAt = Instant.parse("2020-08-29T06:30:00Z");
    PushDevice existing =
        PushDevice.builder()
            .id(11L)
            .userId(3L)
            .installationId(INSTALLATION_ID)
            .fcmToken("old-token")
            .platform(PushPlatform.IOS)
            .lastSeenAt(firstRegisteredAt)
            .createdAt(firstRegisteredAt)
            .updatedAt(firstRegisteredAt)
            .build();
    PushDeviceService service = new PushDeviceService(pushDeviceRepository);
    given(pushDeviceRepository.findByInstallationIdForUpdate(INSTALLATION_ID))
        .willReturn(Optional.of(existing));
    given(pushDeviceRepository.findByFcmTokenForUpdate("rotated-token"))
        .willReturn(Optional.empty());

    service.register(USER_ID, INSTALLATION_ID, "rotated-token", PushPlatform.IOS);

    ArgumentCaptor<PushDevice> savedDevice = ArgumentCaptor.forClass(PushDevice.class);
    verify(pushDeviceRepository).save(savedDevice.capture());
    assertThat(savedDevice.getValue().getId()).isEqualTo(11L);
    assertThat(savedDevice.getValue().getUserId()).isEqualTo(USER_ID);
    assertThat(savedDevice.getValue().getFcmToken()).isEqualTo("rotated-token");
    assertThat(savedDevice.getValue().getCreatedAt()).isEqualTo(firstRegisteredAt);
    assertThat(savedDevice.getValue().getUpdatedAt()).isAfter(firstRegisteredAt);
  }

  /** 같은 토큰이 다른 installation에 남아 있으면 그 오래된 행을 삭제한 뒤 현재 installation을 저장하는지 확인한다. */
  @Test
  void registerMovesTokenFromStaleInstallation() {
    PushDevice staleDevice =
        PushDevice.register(
            3L,
            UUID.fromString("a2cab4bb-a234-4918-a8bc-8d9a9d5d370a"),
            "reused-token",
            PushPlatform.IOS,
            Instant.parse("2020-08-29T06:30:00Z"));
    PushDeviceService service = new PushDeviceService(pushDeviceRepository);
    given(pushDeviceRepository.findByInstallationIdForUpdate(INSTALLATION_ID))
        .willReturn(Optional.empty());
    given(pushDeviceRepository.findByFcmTokenForUpdate("reused-token"))
        .willReturn(Optional.of(staleDevice));

    service.register(USER_ID, INSTALLATION_ID, "reused-token", PushPlatform.IOS);

    InOrder order = inOrder(pushDeviceRepository);
    order.verify(pushDeviceRepository).deleteByFcmToken("reused-token");
    order.verify(pushDeviceRepository).save(any(PushDevice.class));
  }

  /** 토큰과 installation이 이미 같은 행을 가리키면 현재 행을 삭제하지 않고 갱신만 하는지 확인한다. */
  @Test
  void registerDoesNotDeleteCurrentInstallation() {
    PushDevice currentDevice =
        PushDevice.register(
            USER_ID,
            INSTALLATION_ID,
            "current-token",
            PushPlatform.IOS,
            Instant.parse("2020-08-29T06:30:00Z"));
    PushDeviceService service = new PushDeviceService(pushDeviceRepository);
    given(pushDeviceRepository.findByInstallationIdForUpdate(INSTALLATION_ID))
        .willReturn(Optional.of(currentDevice));
    given(pushDeviceRepository.findByFcmTokenForUpdate("current-token"))
        .willReturn(Optional.of(currentDevice));

    service.register(USER_ID, INSTALLATION_ID, "current-token", PushPlatform.IOS);

    verify(pushDeviceRepository, never()).deleteByFcmToken(any());
    verify(pushDeviceRepository).save(any(PushDevice.class));
  }

  /** 로그아웃 삭제가 요청한 사용자와 installation을 함께 넘겨 다른 사용자의 기기를 지우지 않게 하는지 확인한다. */
  @Test
  void deleteScopesInstallationToAuthenticatedUser() {
    PushDeviceService service = new PushDeviceService(pushDeviceRepository);

    service.delete(USER_ID, INSTALLATION_ID);

    verify(pushDeviceRepository).deleteByInstallationIdAndUserId(INSTALLATION_ID, USER_ID);
  }
}
