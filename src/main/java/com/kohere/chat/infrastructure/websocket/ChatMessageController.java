package com.kohere.chat.infrastructure.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kohere.chat.application.ChatTextMessageService;
import com.kohere.chat.application.TextMessageSaveResult;
import com.kohere.chat.presentation.stomp.ChatStompDestinations;
import com.kohere.chat.presentation.stomp.dto.ChatMessageSendPayload;
import com.kohere.common.exception.BusinessException;
import com.kohere.common.exception.ErrorCode;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Controller;

/**
 * 프런트의 STOMP TEXT SEND를 안전한 MySQL 저장 유스케이스에 연결하는 진입점이다.
 *
 * <p>HTTP Controller와 달리 응답 body를 바로 반환하지 않는다. 저장 성공 뒤 room topic에는 공통 원문 이벤트를, 원래 발신 session에는
 * ACK를 각각 보낸다. 개별 메시지 오류는 socket 전체를 닫지 않고 발신 session의 오류 queue로만 전달한다.
 */
@Slf4j
@Controller
public class ChatMessageController {

  private final ChatTextMessageService textMessageService;
  private final ChatRealtimeMessagePublisher realtimePublisher;
  private final ChatStompErrorSender errorSender;
  private final ObjectMapper objectMapper;

  public ChatMessageController(
      ChatTextMessageService textMessageService,
      ChatRealtimeMessagePublisher realtimePublisher,
      ChatStompErrorSender errorSender,
      ObjectMapper objectMapper) {
    this.textMessageService = textMessageService;
    this.realtimePublisher = realtimePublisher;
    this.errorSender = errorSender;
    this.objectMapper = objectMapper;
  }

  /**
   * {@code /app/chat-rooms/{roomId}/messages}의 TEXT를 저장하고 커밋된 결과를 실시간으로 발행한다.
   *
   * @param roomId payload가 아니라 destination에서 서버가 해석한 채팅방 ID
   * @param payload 프런트 UUID와 원문만 가진 TEXT 요청
   * @param headers CONNECT JWT 인증이 저장한 사용자·session 정보
   */
  @MessageMapping(ChatStompDestinations.MESSAGE_APPLICATION_DESTINATION)
  public void sendText(
      @DestinationVariable long roomId,
      @Payload ChatMessageSendPayload payload,
      StompHeaderAccessor headers) {
    long senderId = ChatStompSessionUser.requireUserId(headers);
    String sessionId = ChatStompSessionUser.requireSessionId(headers);
    UUID clientMessageId = payload == null ? null : payload.clientMessageId();

    try {
      /*
       * @Transactional 서비스 프록시가 정상 반환했다는 것은 MySQL COMMIT까지 성공했다는 뜻이다.
       * 그러므로 아래 publisher는 커밋 전 메시지를 상대방에게 보여 주지 않는다.
       */
      TextMessageSaveResult result =
          textMessageService.saveText(
              roomId, senderId, clientMessageId, payload == null ? null : payload.content());
      realtimePublisher.publishTextResult(sessionId, result);
    } catch (BusinessException failure) {
      // 예상 가능한 입력·권한·중복 충돌은 연결을 유지하고 안정적인 code로 실패한 임시 말풍선에만 돌려준다.
      sendKnownError(senderId, sessionId, clientMessageId, failure.getErrorCode());
    } catch (RuntimeException failure) {
      // 원문과 payload는 로그에 넣지 않는다. 예상하지 못한 서버 오류도 해당 메시지만 실패시키고 socket은 유지한다.
      log.error(
          "Unexpected STOMP TEXT failure: userId={}, roomId={}, clientMessageId={}",
          senderId,
          roomId,
          clientMessageId,
          failure);
      sendKnownError(senderId, sessionId, clientMessageId, ErrorCode.INTERNAL_ERROR);
    }
  }

  /**
   * JSON 변환처럼 handler 메서드 호출 전에 발생한 오류도 원래 session의 오류 queue로 보낸다.
   *
   * <p>본문 전체는 저장하거나 로그에 남기지 않는다. JSON에서 UUID만 읽을 수 있으면 임시 말풍선 연결용으로 사용하고, JSON 자체가 깨졌다면 null로 보낸다.
   */
  @MessageExceptionHandler(Exception.class)
  public void handleMappingFailure(Exception failure, Message<?> failedMessage) {
    StompHeaderAccessor headers = StompHeaderAccessor.wrap(failedMessage);
    long userId = ChatStompSessionUser.requireUserId(headers);
    String sessionId = ChatStompSessionUser.requireSessionId(headers);
    UUID clientMessageId = readClientMessageId(failedMessage.getPayload());

    log.debug(
        "Invalid STOMP TEXT payload: userId={}, sessionId={}, clientMessageId={}, exception={}",
        userId,
        sessionId,
        clientMessageId,
        failure.getClass().getSimpleName());
    sendKnownError(userId, sessionId, clientMessageId, ErrorCode.INVALID_INPUT);
  }

  /** 오류 전송 실패가 다시 handler 예외로 번져 WebSocket 전체를 닫지 않도록 마지막 경계를 둔다. */
  private void sendKnownError(
      long userId, String sessionId, UUID clientMessageId, ErrorCode errorCode) {
    try {
      errorSender.send(userId, sessionId, clientMessageId, errorCode);
    } catch (RuntimeException errorDeliveryFailure) {
      log.error(
          "STOMP error event delivery failed: userId={}, clientMessageId={}, code={}",
          userId,
          clientMessageId,
          errorCode.getCode(),
          errorDeliveryFailure);
    }
  }

  /** 변환 실패한 원본 payload에서 민감한 content는 버리고 UUID 한 필드만 최선 노력으로 읽는다. */
  private UUID readClientMessageId(Object rawPayload) {
    if (rawPayload instanceof ChatMessageSendPayload payload) {
      return payload.clientMessageId();
    }
    try {
      JsonNode root;
      if (rawPayload instanceof byte[] bytes) {
        root = objectMapper.readTree(bytes);
      } else if (rawPayload instanceof String text) {
        root = objectMapper.readTree(text);
      } else {
        return null;
      }
      JsonNode id = root == null ? null : root.get("clientMessageId");
      return id == null || !id.isTextual() ? null : UUID.fromString(id.textValue());
    } catch (Exception ignored) {
      return null;
    }
  }
}
