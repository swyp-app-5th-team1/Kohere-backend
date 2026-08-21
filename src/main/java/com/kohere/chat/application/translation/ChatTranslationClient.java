package com.kohere.chat.application.translation;

/** Google 같은 외부 번역 제공자를 애플리케이션 계층에서 호출하는 포트다. */
public interface ChatTranslationClient {

  /**
   * 원문 언어를 자동 감지하고 지정한 언어로 plain text를 번역한다.
   *
   * @param originalContent 사용자가 보낸 수정하지 않은 원문
   * @param targetLanguage 수신자의 표시 언어 ko 또는 en
   * @return 번역문과 실제 감지된 원문 언어
   * @throws ChatTranslationClientException 재시도 여부를 분류할 수 있는 외부 호출 실패
   */
  ChatTranslationClientResult translate(String originalContent, String targetLanguage);
}
