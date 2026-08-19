package com.kohere.chat.presentation;

import com.kohere.chat.application.ChatService;
import com.kohere.chat.application.dto.ChatRoomResponse;
import com.kohere.chat.application.dto.MessageResponse;
import com.kohere.common.response.ApiResponse;
import com.kohere.common.response.CursorResponse;
import com.kohere.common.response.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 채팅방·메시지 REST 컨트롤러. 입력 검증·DTO 변환만 담당하고 비즈니스 로직은 응용 계층에 위임한다 (docs/convention/code-style.md §3-3).
 * 응답은 공통 래퍼로 감싼다.
 *
 * <p>스펙: docs/architecture/chat/02-api-contracts.md. 리스트는 오프셋 페이지네이션, 메시지 조회는 커서 페이지네이션이다.
 *
 * <p>TODO: 본인이 참여하지 않은 방 접근 시 403 FORBIDDEN 처리는 응용 계층에서 인증 주체로 검증한다.
 *
 * <p>TODO: 읽음 기능 고도화 시 lastReadMessageId와 unreadCount 계약을 새로 확정한 뒤 {@code POST
 * /api/v1/chat-rooms/{roomId}/read}를 구현한다. 현재 메시지 전송은 REST가 아니라 STOMP {@code SEND
 * /app/chat-rooms/{roomId}/messages}로만 제공한다.
 */
@RestController
@RequestMapping("/api/v1/chat-rooms")
@RequiredArgsConstructor
public class ChatRoomController {

  private final ChatService chatService;

  @GetMapping
  public ApiResponse<PageResponse<ChatRoomResponse>> listRooms(
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "size", defaultValue = "20") int size) {
    return ApiResponse.success(chatService.listRooms(page, size));
  }

  @GetMapping("/{roomId}/messages")
  public ApiResponse<CursorResponse<MessageResponse>> getMessages(
      @PathVariable Long roomId,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "afterMessageId", required = false) String afterMessageId,
      @RequestParam(name = "size", defaultValue = "30") int size) {
    return ApiResponse.success(chatService.getMessages(roomId, cursor, afterMessageId, size));
  }
}
