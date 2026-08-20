package com.kohere.chat.presentation.stomp.dto;

import java.util.UUID;

/**
 * 개별 TEXT SEND가 거부됐을 때 원래 발신 session에만 전달하는 오류다.
 *
 * <p>CONNECT 인증 실패처럼 연결 전체를 닫는 오류와 구분한다. 프런트엔드는 {@code clientMessageId}로 실패한 임시 말풍선만 찾아 재시도 여부를
 * 표시하며, 재시도할 때는 새 UUID가 아니라 같은 UUID를 사용한다.
 */
public record ChatMessageErrorPayload(
    /** payload 계약 버전. 현재 값은 1이다. */
    int version,
    /** 오류가 발생한 프런트엔드 SEND의 UUID. */
    UUID clientMessageId,
    /** 앱이 분기 기준으로 사용하는 안정적인 오류 code. */
    String code,
    /** 사용자에게 보여 줄 수 있는 현지화된 설명. 앱 로직은 이 문자열로 분기하지 않는다. */
    String message) {}
