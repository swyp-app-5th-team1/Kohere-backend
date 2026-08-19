package com.kohere.chat.application.dto;

import com.kohere.chat.domain.TranslationProvider;

/** 로그인 사용자의 표시 언어로 생성된 TEXT 번역본. 번역본이 없으면 메시지 응답에서 이 객체가 null이다. */
public record MessageTranslationResponse(
    String content, String sourceLanguage, String targetLanguage, TranslationProvider provider) {}
