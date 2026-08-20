package com.kohere.chat.application.dto;

import com.kohere.chat.domain.TranslationProvider;

/**
 * 로그인 사용자의 표시 언어로 생성된 TEXT 번역 결과다.
 *
 * <p>원문과 별도 객체로 두어 프런트엔드가 번역문을 기본 표시하면서도 원문 보기를 지원할 수 있다. 이 객체가 {@code null}이어도 원문 저장 실패를 뜻하지 않으며,
 * 번역 미완료·불필요·최종 실패 중 화면에서 필요한 처리는 원문을 기준으로 한다.
 */
public record MessageTranslationResponse(
    /** 번역된 TEXT. 원문을 덮어쓰지 않는다. */
    String content,
    /** 번역 provider가 감지한 원문 언어 code. 사용자 프로필 언어로 추정하지 않는다. */
    String sourceLanguage,
    /** 수신자의 현재 표시 언어에서 서버가 결정한 대상 언어 code. */
    String targetLanguage,
    /** 번역 출처 표기와 운영 추적에 사용할 provider. */
    TranslationProvider provider) {}
