package com.kohere.chat.infrastructure.websocket;

import com.kohere.chat.presentation.stomp.ChatStompDestinations;
import com.kohere.chat.presentation.stomp.dto.ChatMessageErrorPayload;
import com.kohere.common.exception.ErrorCode;
import com.kohere.user.api.UserAccountService;
import java.util.Locale;
import java.util.UUID;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

/** 개별 TEXT SEND 오류를 로그인 사용자의 표시 언어로 만들어 원래 WebSocket session에만 보낸다. */
@Component
public class ChatStompErrorSender {

  private static final int PAYLOAD_VERSION = 1;

  private final ChatSessionMessageSender sessionMessageSender;
  private final UserAccountService userAccountService;
  private final MessageSource messageSource;

  public ChatStompErrorSender(
      ChatSessionMessageSender sessionMessageSender,
      UserAccountService userAccountService,
      MessageSource messageSource) {
    this.sessionMessageSender = sessionMessageSender;
    this.userAccountService = userAccountService;
    this.messageSource = messageSource;
  }

  /**
   * 오류 code는 앱 분기용으로 안정적으로 유지하고, message만 현재 지원 언어인 ko/en으로 현지화한다.
   *
   * @param userId CONNECT JWT에서 검증한 사용자 ID
   * @param sessionId 실패한 SEND를 보낸 socket session ID
   * @param clientMessageId 실패한 임시 말풍선 UUID. JSON 자체를 해석하지 못한 경우 null일 수 있다.
   * @param errorCode 안정적인 오류 code와 기본 문구
   */
  public void send(long userId, String sessionId, UUID clientMessageId, ErrorCode errorCode) {
    Locale locale = resolveLocale(userId);
    String message =
        messageSource.getMessage(errorCode.getCode(), null, errorCode.getDefaultMessage(), locale);

    sessionMessageSender.sendToSession(
        sessionId,
        ChatStompDestinations.ERROR_USER_DESTINATION,
        new ChatMessageErrorPayload(
            PAYLOAD_VERSION, clientMessageId, errorCode.getCode(), message));
  }

  /** 언어 조회 자체가 실패한 오류 경로에서도 원래 오류를 잃지 않도록 영어로 안전하게 폴백한다. */
  private Locale resolveLocale(long userId) {
    try {
      String language = userAccountService.getLanguage(userId);
      return "ko".equalsIgnoreCase(language) ? Locale.KOREAN : Locale.ENGLISH;
    } catch (RuntimeException ignored) {
      return Locale.ENGLISH;
    }
  }
}
