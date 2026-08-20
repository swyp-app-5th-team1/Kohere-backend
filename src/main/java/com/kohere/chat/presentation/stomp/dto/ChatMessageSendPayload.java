package com.kohere.chat.presentation.stomp.dto;

import com.kohere.chat.domain.Message;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * 프런트엔드가 STOMP로 보내는 TEXT 메시지 요청이다.
 *
 * <p>{@code roomId}와 발신자 ID를 body로 받지 않는 이유는 각각 destination과 검증된 STOMP Principal이 정본이기 때문이다. 프런트엔드는
 * 전송 직전에 UUID를 만들고, 응답을 못 받아 같은 메시지를 다시 보낼 때 반드시 같은 UUID를 사용해야 서버 UNIQUE 제약이 중복 저장을 막을 수 있다.
 */
public record ChatMessageSendPayload(
    /** 프런트엔드가 생성하는 멱등 키. 같은 사용자 메시지의 재시도에는 같은 UUID를 사용한다. */
    @NotNull UUID clientMessageId,
    /** 서버가 원문 그대로 저장할 TEXT. 발신자·대상 언어·번역문은 포함하지 않는다. */
    @NotBlank String content) {

  /** STOMP frame 제한과 별개인 제품 정책상 원문 최대 Unicode code point 수. */
  public static final int MAX_CONTENT_CODE_POINTS = Message.MAX_TEXT_CODE_POINTS;
}
