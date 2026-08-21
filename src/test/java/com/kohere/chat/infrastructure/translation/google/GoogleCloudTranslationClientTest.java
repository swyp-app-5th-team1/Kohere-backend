package com.kohere.chat.infrastructure.translation.google;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.google.cloud.translate.v3.TranslateTextRequest;
import com.google.cloud.translate.v3.TranslateTextResponse;
import com.google.cloud.translate.v3.Translation;
import com.google.cloud.translate.v3.TranslationServiceClient;
import com.kohere.chat.application.translation.ChatTranslationClientResult;
import com.kohere.chat.infrastructure.translation.ChatTranslationProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/** 실제 네트워크 없이 Google v3 요청의 project·location·언어·plain text 계약을 확인한다. */
class GoogleCloudTranslationClientTest {

  @Test
  void sendsPlainTextToConfiguredProjectAndReturnsDetectedLanguage() {
    TranslationServiceClient googleClient = Mockito.mock(TranslationServiceClient.class);
    given(googleClient.translateText(any(TranslateTextRequest.class)))
        .willReturn(
            TranslateTextResponse.newBuilder()
                .addTranslations(
                    Translation.newBuilder()
                        .setTranslatedText("Hello")
                        .setDetectedLanguageCode("ko")
                        .build())
                .build());
    ChatTranslationProperties properties = new ChatTranslationProperties();
    properties.setProjectId("project-bdb9704d-3952-475b-a1c");
    properties.setLocation("global");
    GoogleCloudTranslationClient client =
        new GoogleCloudTranslationClient(googleClient, properties);

    ChatTranslationClientResult result = client.translate("안녕하세요", "en");

    ArgumentCaptor<TranslateTextRequest> request =
        ArgumentCaptor.forClass(TranslateTextRequest.class);
    verify(googleClient).translateText(request.capture());
    assertThat(request.getValue().getParent())
        .isEqualTo("projects/project-bdb9704d-3952-475b-a1c/locations/global");
    assertThat(request.getValue().getMimeType()).isEqualTo("text/plain");
    assertThat(request.getValue().getTargetLanguageCode()).isEqualTo("en");
    assertThat(request.getValue().getContentsList()).containsExactly("안녕하세요");
    assertThat(result.translatedContent()).isEqualTo("Hello");
    assertThat(result.detectedSourceLanguage()).isEqualTo("ko");
  }
}
