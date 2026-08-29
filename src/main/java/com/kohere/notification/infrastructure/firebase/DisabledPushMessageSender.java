package com.kohere.notification.infrastructure.firebase;

import com.kohere.notification.application.PushMessage;
import com.kohere.notification.application.PushMessageSender;
import com.kohere.notification.application.PushSendResult;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Firebase를 켜지 않은 local/test 환경에서 네트워크 호출 없이 발송을 의도적으로 건너뛴다. */
@Component
@ConditionalOnProperty(
    prefix = "app.firebase",
    name = "enabled",
    havingValue = "false",
    matchIfMissing = true)
public class DisabledPushMessageSender implements PushMessageSender {

  /** 각 입력 토큰에 SKIPPED 결과를 돌려 listener의 개수·정리 계약을 그대로 유지한다. */
  @Override
  public List<PushSendResult> send(PushMessage message) {
    return message.fcmTokens().stream()
        .map(token -> new PushSendResult(token, PushSendResult.Status.SKIPPED))
        .toList();
  }
}
