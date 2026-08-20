package com.kohere.chat.infrastructure.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kohere.chat.presentation.stomp.dto.ChatStompConnectErrorPayload;
import com.kohere.common.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;

/**
 * CONNECT·inbound 처리 실패를 내부 예외가 노출되지 않는 STOMP ERROR frame으로 바꾼다.
 *
 * <p>기본 오류 handler에 예외 문자열을 그대로 맡기면 JWT parser나 클래스 이름이 클라이언트에 보일 수 있다. 원인을 공통 코드로만 축약하고
 * token·header·stack trace는 body와 로그에 넣지 않는다.
 */
@Component
@RequiredArgsConstructor
public class ChatStompConnectErrorHandler extends StompSubProtocolErrorHandler {

  private final ObjectMapper objectMapper;

  /** 예외 chain에서 우리가 만든 인증·인가 오류만 찾고 나머지는 일반 서버 오류로 숨긴다. */
  @Override
  public Message<byte[]> handleClientMessageProcessingError(
      Message<byte[]> clientMessage, Throwable ex) {
    ErrorCode errorCode = resolveErrorCode(ex);
    byte[] body = serialize(errorCode);

    StompHeaderAccessor errorAccessor = StompHeaderAccessor.create(StompCommand.ERROR);
    errorAccessor.setMessage(errorCode.getCode());
    errorAccessor.setContentType(MediaType.APPLICATION_JSON);
    errorAccessor.setLeaveMutable(true);

    return MessageBuilder.createMessage(body, errorAccessor.getMessageHeaders());
  }

  /** 중첩된 MessageDeliveryException 안쪽까지 확인하되 원본 예외 메시지는 반환하지 않는다. */
  private static ErrorCode resolveErrorCode(Throwable error) {
    Throwable current = error;
    while (current != null) {
      if (current instanceof ChatStompAuthenticationException authenticationException) {
        return authenticationException.getErrorCode();
      }
      if (current instanceof AccessDeniedException) {
        return ErrorCode.FORBIDDEN;
      }
      current = current.getCause();
    }
    return ErrorCode.INTERNAL_ERROR;
  }

  /** ObjectMapper 실패 자체로 원래 보안 오류가 사라지지 않도록 최소 JSON으로 폴백한다. */
  private byte[] serialize(ErrorCode errorCode) {
    var payload =
        new ChatStompConnectErrorPayload(1, errorCode.getCode(), errorCode.getDefaultMessage());
    try {
      return objectMapper.writeValueAsBytes(payload);
    } catch (JsonProcessingException e) {
      String fallback =
          "{\"version\":1,\"code\":\"INTERNAL_ERROR\",\"message\":\"Connection failed\"}";
      return fallback.getBytes(StandardCharsets.UTF_8);
    }
  }
}
