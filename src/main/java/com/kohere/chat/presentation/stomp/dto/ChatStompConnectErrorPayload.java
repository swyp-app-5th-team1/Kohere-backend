package com.kohere.chat.presentation.stomp.dto;

/**
 * STOMP CONNECT가 실패했을 때 ERROR frame body에 담는 안전한 응답이다.
 *
 * <p>JWT 문자열과 내부 예외 메시지는 보내지 않는다. 앱은 {@code code}로 토큰 갱신·온보딩 화면 이동을 결정한다.
 */
public record ChatStompConnectErrorPayload(
    /** payload 계약 버전. 현재 값은 1이다. */
    int version,
    /**
     * {@code UNAUTHENTICATED}, {@code TOKEN_EXPIRED}, {@code AUTH_ONBOARDING_REQUIRED} 같은 안정적인 코드.
     */
    String code,
    /** 사용자에게 표시할 수 있는 짧은 기본 안내. 앱의 로직 분기에는 사용하지 않는다. */
    String message) {}
