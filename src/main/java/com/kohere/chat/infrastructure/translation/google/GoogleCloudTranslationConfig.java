package com.kohere.chat.infrastructure.translation.google;

import com.google.api.gax.retrying.RetrySettings;
import com.google.cloud.translate.v3.TranslationServiceClient;
import com.google.cloud.translate.v3.TranslationServiceSettings;
import com.kohere.chat.infrastructure.translation.ChatTranslationProperties;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.threeten.bp.Duration;

/** ADC로 인증하는 Cloud Translation Advanced v3 client를 번역 활성 환경에서만 만든다. */
@Configuration
@ConditionalOnProperty(prefix = "app.chat.translation", name = "enabled", havingValue = "true")
public class GoogleCloudTranslationConfig {

  /**
   * Google SDK 내부 재시도를 1회로 제한한다.
   *
   * <p>재시도 횟수는 DB {@code attempt_count}가 정본이어야 한다. SDK가 내부에서 몰래 여러 번 호출하면 운영 비용과 최대 5회 계약을 추적할 수
   * 없으므로 Worker가 직접 재시도한다.
   */
  @Bean(destroyMethod = "close")
  public TranslationServiceClient translationServiceClient(ChatTranslationProperties properties)
      throws IOException {
    TranslationServiceSettings.Builder settings = TranslationServiceSettings.newBuilder();
    Duration requestTimeout = Duration.ofNanos(properties.getRequestTimeout().toNanos());
    RetrySettings singleAttempt =
        settings.translateTextSettings().getRetrySettings().toBuilder()
            .setMaxAttempts(1)
            .setInitialRpcTimeout(requestTimeout)
            .setMaxRpcTimeout(requestTimeout)
            .setTotalTimeout(requestTimeout)
            .build();
    settings.translateTextSettings().setRetrySettings(singleAttempt);
    return TranslationServiceClient.create(settings.build());
  }
}
