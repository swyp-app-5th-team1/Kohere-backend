package com.kohere.notification.application;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.kohere.chat.ChatMessageCreatedEvent;
import com.kohere.chat.ChatMessageKind;
import com.kohere.notification.domain.PushDevice;
import com.kohere.notification.domain.PushDeviceRepository;
import com.kohere.notification.domain.PushPlatform;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 채팅 푸시 listener의 수신자 기기 조회와 영구 무효 토큰 정리 경계를 검증한다. */
@ExtendWith(MockitoExtension.class)
class ChatMessagePushListenerTest {

  @Mock private PushDeviceRepository pushDeviceRepository;
  @Mock private ChatPushMessageFactory messageFactory;
  @Mock private PushMessageSender messageSender;

  /** 등록 기기가 없는 수신자에게는 payload 생성과 provider 호출을 모두 생략한다. */
  @Test
  @DisplayName("수신자 기기가 없으면 FCM 발송을 건너뛴다")
  void skipsRecipientWithoutDevice() {
    ChatMessageCreatedEvent event = event();
    given(pushDeviceRepository.findAllByUserId(event.recipientUserId())).willReturn(List.of());
    ChatMessagePushListener listener = listener();

    listener.onChatMessageCreated(event);

    verifyNoInteractions(messageFactory, messageSender);
  }

  /** 여러 기기 결과 중 UNREGISTERED로 분류된 토큰만 삭제하고 다른 실패 토큰은 유지한다. */
  @Test
  @DisplayName("영구 무효 토큰만 DB에서 삭제한다")
  void deletesOnlyInvalidToken() {
    ChatMessageCreatedEvent event = event();
    List<PushDevice> devices =
        List.of(
            device(1L, "sent-token"),
            device(2L, "invalid-token"),
            device(3L, "temporary-token"),
            device(4L, "configuration-token"));
    List<String> tokens = devices.stream().map(PushDevice::getFcmToken).toList();
    PushMessage pushMessage =
        new PushMessage(tokens, "채팅", "새 메시지", Map.of("type", "CHAT_MESSAGE"));
    given(pushDeviceRepository.findAllByUserId(event.recipientUserId())).willReturn(devices);
    given(messageFactory.create(event, tokens)).willReturn(pushMessage);
    given(messageSender.send(pushMessage))
        .willReturn(
            List.of(
                result("sent-token", PushSendResult.Status.SENT),
                result("invalid-token", PushSendResult.Status.INVALID_TOKEN),
                result("temporary-token", PushSendResult.Status.TEMPORARY_FAILURE),
                result("configuration-token", PushSendResult.Status.CONFIGURATION_FAILURE)));
    given(pushDeviceRepository.deleteByFcmToken("invalid-token")).willReturn(true);
    ChatMessagePushListener listener = listener();

    listener.onChatMessageCreated(event);

    verify(pushDeviceRepository).deleteByFcmToken("invalid-token");
    verify(pushDeviceRepository, never()).deleteByFcmToken("temporary-token");
    verify(pushDeviceRepository, never()).deleteByFcmToken("configuration-token");
    verify(pushDeviceRepository, never()).deleteByFcmToken("sent-token");
  }

  /** 테스트 대상 listener에 mock 저장소·factory·발송 포트를 연결한다. */
  private ChatMessagePushListener listener() {
    return new ChatMessagePushListener(pushDeviceRepository, messageFactory, messageSender);
  }

  /** 상태별 provider 응답 fixture를 간단히 만든다. */
  private static PushSendResult result(String token, PushSendResult.Status status) {
    return new PushSendResult(token, status);
  }

  /** 사용자 한 명의 서로 다른 설치본과 토큰을 나타내는 기기 fixture를 만든다. */
  private static PushDevice device(long id, String token) {
    Instant now = Instant.parse("2026-08-29T06:30:00Z");
    return PushDevice.builder()
        .id(id)
        .userId(77L)
        .installationId(new UUID(0L, id))
        .fcmToken(token)
        .platform(PushPlatform.IOS)
        .lastSeenAt(now)
        .createdAt(now)
        .updatedAt(now)
        .build();
  }

  /** 모든 listener 테스트가 공유할 신규 TEXT 이벤트를 만든다. */
  private static ChatMessageCreatedEvent event() {
    return new ChatMessageCreatedEvent(
        UUID.fromString("61ee3bde-2015-4317-9d68-460955520154"),
        ChatMessageKind.TEXT,
        10L,
        125L,
        77L,
        "listing-1",
        "고시원3",
        Instant.parse("2026-08-29T06:30:00Z"));
  }
}
