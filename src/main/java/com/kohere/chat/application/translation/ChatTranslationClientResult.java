package com.kohere.chat.application.translation;

/** 외부 provider가 반환한 번역문과 감지 원문 언어다. */
public record ChatTranslationClientResult(
    /** 번역된 plain text. */
    String translatedContent,
    /** provider가 본문에서 감지한 ISO 언어 code. */
    String detectedSourceLanguage) {}
