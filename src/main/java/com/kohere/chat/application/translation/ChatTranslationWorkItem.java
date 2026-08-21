package com.kohere.chat.application.translation;

import com.kohere.chat.domain.Message;
import com.kohere.chat.domain.translation.ChatMessageTranslation;

/** Worker가 DB lock을 푼 뒤 Google을 호출하는 데 필요한 원문과 번역 작업 snapshot이다. */
public record ChatTranslationWorkItem(
    /** 시도 횟수와 대상 언어를 가진 번역 작업. */
    ChatMessageTranslation translation,
    /** 수정하지 않은 TEXT 원문 메시지. */
    Message originalMessage) {}
