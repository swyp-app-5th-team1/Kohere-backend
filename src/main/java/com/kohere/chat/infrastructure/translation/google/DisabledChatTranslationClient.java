package com.kohere.chat.infrastructure.translation.google;

import com.kohere.chat.application.translation.ChatTranslationClient;
import com.kohere.chat.application.translation.ChatTranslationClientException;
import com.kohere.chat.application.translation.ChatTranslationClientResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 자격증명을 준비하지 않은 로컬·테스트 환경에서도 원문 실시간 전달을 유지하는 안전한 fallback이다.
 *
 * <p>Google 호출을 하지 않고 Worker가 즉시 FAILED를 저장하게 한다. 배포 환경은 환경변수로 실제 어댑터를 선택한다.
 */
@Component
@ConditionalOnProperty(
    prefix = "app.chat.translation",
    name = "enabled",
    havingValue = "false",
    matchIfMissing = true)
public class DisabledChatTranslationClient implements ChatTranslationClient {

  /** {@inheritDoc} */
  @Override
  public ChatTranslationClientResult translate(String originalContent, String targetLanguage) {
    throw new ChatTranslationClientException("PROVIDER_DISABLED", false, null);
  }
}
