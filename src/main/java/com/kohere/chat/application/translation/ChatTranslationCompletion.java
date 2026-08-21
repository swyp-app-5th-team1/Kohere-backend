package com.kohere.chat.application.translation;

import com.kohere.chat.domain.Message;
import com.kohere.chat.domain.translation.ChatMessageTranslation;

/** 최종 상태 커밋 뒤 개인 STOMP queue에 함께 실어 보낼 원문과 번역 결과다. */
public record ChatTranslationCompletion(
    /** 수신자가 원문 보기에서 사용할 TEXT 정본. */
    Message originalMessage,
    /** SUCCEEDED, NOT_REQUIRED 또는 FAILED로 확정된 사용자별 결과. */
    ChatMessageTranslation translation) {}
