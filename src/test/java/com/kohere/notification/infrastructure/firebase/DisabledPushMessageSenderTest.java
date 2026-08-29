package com.kohere.notification.infrastructure.firebase;

import static org.assertj.core.api.Assertions.assertThat;

import com.kohere.notification.application.PushMessage;
import com.kohere.notification.application.PushSendResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Firebase 비활성 환경이 입력 토큰 순서와 개수를 유지한 SKIPPED 결과를 반환하는지 검증한다. */
class DisabledPushMessageSenderTest {

  /** 외부 호출 없이 모든 대상 토큰을 의도적인 건너뛰기로 처리한다. */
  @Test
  @DisplayName("Firebase가 꺼져 있으면 모든 발송을 건너뛴다")
  void skipsEveryTarget() {
    PushMessage message =
        new PushMessage(
            List.of("token-a", "token-b"), "채팅", "새 메시지", Map.of("type", "CHAT_MESSAGE"));

    List<PushSendResult> results = new DisabledPushMessageSender().send(message);

    assertThat(results).extracting(PushSendResult::fcmToken).containsExactly("token-a", "token-b");
    assertThat(results)
        .extracting(PushSendResult::status)
        .containsOnly(PushSendResult.Status.SKIPPED);
  }
}
