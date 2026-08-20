package com.kohere.chat.presentation;

import com.kohere.chat.presentation.dto.ChatStompGuideResponse;
import com.kohere.common.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 프런트엔드가 Swagger에서 실시간 채팅 연결 계약을 쉽게 찾게 하는 읽기 전용 REST 안내 진입점이다.
 *
 * <p>OpenAPI와 Swagger UI는 HTTP API 문서 도구라 STOMP SEND·SUBSCRIBE 자체를 실행할 수 없다. 그래서 실행 가능한 실제 STOMP
 * endpoint를 REST API로 바꾸는 대신, 연결 주소·destination·개인 queue를 이 안내 응답으로 제공한다. 프런트는 안내를 읽은 뒤
 * WebSocket/STOMP 클라이언트에서 실제 연결을 수행한다.
 */
@RestController
@RequestMapping("/api/v1/chat")
public class ChatStompGuideController {

  /**
   * 현재 서버가 사용하는 실시간 채팅 STOMP 연결 정보를 반환한다.
   *
   * <p>응답에는 JWT 자체가 포함되지 않는다. 앱은 로그인으로 받은 access token을 STOMP CONNECT의 native {@code Authorization}
   * header에 직접 넣어야 한다.
   *
   * @return WebSocket 주소, SEND·SUBSCRIBE 경로, 개인 queue와 메시지 제한값
   */
  @GetMapping("/stomp-guide")
  public ApiResponse<ChatStompGuideResponse> getGuide() {
    return ApiResponse.success(ChatStompGuideResponse.currentContract());
  }
}
