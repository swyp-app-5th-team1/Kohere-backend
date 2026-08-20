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
 * 채팅방 목록과 저장된 메시지 이력을 조회하는 REST 진입점이다.
 *
 * <p>REST는 MySQL에 저장된 정본을 페이지 단위로 읽는 역할만 한다. 실시간 메시지 전송은 STOMP 한 경로로 통일해 REST와 STOMP가 서로 다른 중복
 * 처리·ACK 규칙을 갖지 않게 한다. 읽음 위치와 {@code unreadCount}도 아직 제품 계약이 확정되지 않았으므로 이번 API에 노출하지 않는다.
 *
 * <p>목록은 일반 화면용 오프셋 페이지, 메시지는 과거 스크롤과 재연결 누락 보충을 모두 지원하는 커서 페이지를 사용한다. 입력 해석만 담당하며 참여자 검증과 사용자별 숨김
 * 경계는 응용 계층에 위임한다.
 *
 * <p>계약: docs/architecture/chat/02-api-contracts.md.
 */
@RestController
@RequestMapping("/api/v1/chat-rooms")
@RequiredArgsConstructor
public class ChatRoomController {

  private final ChatService chatService;

  /**
   * 현재 사용자에게 보이는 채팅방을 최근 활동 순으로 조회한다.
   *
   * <p>{@code category}와 {@code unreadCount}는 현재 범위가 아니며, 삭제로 숨긴 방의 내부 상태도 목록에 포함하지 않는다.
   *
   * @param page 0부터 시작하는 페이지 번호
   * @param size 한 페이지 항목 수
   * @return 채팅방 항목과 오프셋 페이지 정보
   */
  @GetMapping
  public ApiResponse<PageResponse<ChatRoomResponse>> listRooms(
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "size", defaultValue = "20") int size) {
    return ApiResponse.success(chatService.listRooms(page, size));
  }

  /**
   * MySQL에 저장된 메시지를 과거 스크롤 또는 WebSocket 재연결 누락 보충 목적으로 조회한다.
   *
   * <p>{@code cursor}는 더 오래된 메시지, {@code afterMessageId}는 끊긴 동안 생긴 더 새 메시지를 찾는다. 두 모드는 동시에 쓰지 않으며 이
   * API는 전송 endpoint가 아니다.
   *
   * @param roomId 조회할 채팅방의 서버 식별자
   * @param cursor 이 메시지 ID보다 오래된 이력을 조회하는 커서
   * @param afterMessageId 이 메시지 ID보다 새로 저장된 누락분을 조회하는 기준
   * @param size 한 번에 조회할 메시지 수
   * @return 메시지와 다음 조회용 커서 정보
   */
  @GetMapping("/{roomId}/messages")
  public ApiResponse<CursorResponse<MessageResponse>> getMessages(
      @PathVariable Long roomId,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "afterMessageId", required = false) String afterMessageId,
      @RequestParam(name = "size", defaultValue = "30") int size) {
    return ApiResponse.success(chatService.getMessages(roomId, cursor, afterMessageId, size));
  }
}
