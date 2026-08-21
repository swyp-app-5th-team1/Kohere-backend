package com.kohere.chat.infrastructure.translation.google;

import com.google.api.gax.rpc.ApiException;
import com.google.api.gax.rpc.StatusCode;
import com.google.cloud.translate.v3.LocationName;
import com.google.cloud.translate.v3.TranslateTextRequest;
import com.google.cloud.translate.v3.TranslateTextResponse;
import com.google.cloud.translate.v3.Translation;
import com.google.cloud.translate.v3.TranslationServiceClient;
import com.kohere.chat.application.translation.ChatTranslationClient;
import com.kohere.chat.application.translation.ChatTranslationClientException;
import com.kohere.chat.application.translation.ChatTranslationClientResult;
import com.kohere.chat.infrastructure.translation.ChatTranslationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Google Cloud Translation Advanced v3에 plain text 번역을 요청하는 실제 어댑터다. */
@Component
@ConditionalOnProperty(prefix = "app.chat.translation", name = "enabled", havingValue = "true")
public class GoogleCloudTranslationClient implements ChatTranslationClient {

  private final TranslationServiceClient client;
  private final String parent;

  public GoogleCloudTranslationClient(
      TranslationServiceClient client, ChatTranslationProperties properties) {
    this.client = client;
    this.parent = LocationName.of(properties.getProjectId(), properties.getLocation()).toString();
  }

  /** {@inheritDoc} */
  @Override
  public ChatTranslationClientResult translate(String originalContent, String targetLanguage) {
    TranslateTextRequest request =
        TranslateTextRequest.newBuilder()
            .setParent(parent)
            .setMimeType("text/plain")
            .setTargetLanguageCode(targetLanguage)
            .addContents(originalContent)
            .build();

    try {
      TranslateTextResponse response = client.translateText(request);
      if (response.getTranslationsCount() != 1) {
        throw new ChatTranslationClientException("INVALID_PROVIDER_RESPONSE", false, null);
      }
      Translation translated = response.getTranslations(0);
      return new ChatTranslationClientResult(
          translated.getTranslatedText(), translated.getDetectedLanguageCode());
    } catch (ApiException failure) {
      StatusCode.Code code = failure.getStatusCode().getCode();
      throw new ChatTranslationClientException("GOOGLE_" + code.name(), isRetryable(code), failure);
    }
  }

  /** timeout·429·일시적 Google 서버 오류만 짧게 재시도한다. */
  private static boolean isRetryable(StatusCode.Code code) {
    return switch (code) {
      case DEADLINE_EXCEEDED, RESOURCE_EXHAUSTED, UNAVAILABLE, INTERNAL, ABORTED -> true;
      default -> false;
    };
  }
}
