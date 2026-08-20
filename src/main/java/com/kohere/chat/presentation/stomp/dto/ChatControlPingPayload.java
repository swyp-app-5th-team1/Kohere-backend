package com.kohere.chat.presentation.stomp.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * 개인 control queue가 실제로 수신 가능한지 확인하기 위해 프런트엔드가 보내는 ping이다.
 *
 * <p>WebSocket 연결 성공과 user queue 구독 준비는 같은 사건이 아니므로 request/response를 한 쌍으로 확인한다.
 */
public record ChatControlPingPayload(
    /** 프런트와 서버가 payload 모양을 구분하는 계약 버전. 현재 값은 1이다. */
    @Min(1) @Max(1) int version,
    /** 여러 ping 중 응답을 정확히 연결하기 위해 프런트엔드가 만든 요청 UUID. */
    @NotNull UUID requestId) {

  public static final int CURRENT_VERSION = 1;
}
