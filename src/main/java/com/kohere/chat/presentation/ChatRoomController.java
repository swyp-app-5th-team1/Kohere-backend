package com.kohere.chat.presentation;

import com.kohere.chat.application.ChatMessageHistoryService;
import com.kohere.chat.application.ChatRoomBlockService;
import com.kohere.chat.application.ChatRoomDeletionService;
import com.kohere.chat.application.ChatRoomDetailService;
import com.kohere.chat.application.ChatRoomListService;
import com.kohere.chat.application.ChatService;
import com.kohere.chat.application.dto.ChatRoomDetailResponse;
import com.kohere.chat.application.dto.ChatRoomResponse;
import com.kohere.chat.application.dto.MessageResponse;
import com.kohere.common.response.ApiResponse;
import com.kohere.common.response.CursorResponse;
import com.kohere.common.response.PageResponse;
import com.kohere.common.security.AuthPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 채팅방 목록·단건 기본 정보와 저장된 메시지 이력을 조회하는 REST 진입점이다.
 *
 * <p>REST는 MySQL에 저장된 정본을 페이지 단위로 읽는 역할만 한다. 실시간 메시지 전송은 STOMP 한 경로로 통일해 REST와 STOMP가 서로 다른 중복
 * 처리·ACK 규칙을 갖지 않게 한다. 읽음 위치와 {@code unreadCount}도 아직 제품 계약이 확정되지 않았으므로 이번 API에 노출하지 않는다.
 *
 * <p>목록은 일반 화면용 오프셋 페이지, 단건은 알림·딥링크용 방 헤더, 메시지는 과거 스크롤과 재연결 누락 보충을 모두 지원하는 커서 페이지를 사용한다. 입력 해석만
 * 담당하며 참여자 검증과 사용자별 숨김 경계는 응용 계층에 위임한다.
 *
 * <p>계약: docs/architecture/chat/02-api-contracts.md.
 */
@RestController
@RequestMapping("/api/v1/chat-rooms")
@RequiredArgsConstructor
public class ChatRoomController {

  private final ChatService chatService;
  private final ChatRoomListService chatRoomListService;
  private final ChatRoomDetailService chatRoomDetailService;
  private final ChatMessageHistoryService chatMessageHistoryService;
  private final ChatRoomDeletionService chatRoomDeletionService;
  private final ChatRoomBlockService chatRoomBlockService;

  /**
   * 현재 사용자에게 보이는 채팅방을 최근 활동 순으로 조회한다.
   *
   * <p>{@code category}와 {@code unreadCount}는 현재 범위가 아니며, 삭제로 숨긴 방의 내부 상태도 목록에 포함하지 않는다.
   *
   * @param principal 검증된 access token에서 만든 로그인 사용자 정보
   * @param page 0부터 시작하는 페이지 번호
   * @param size 한 페이지 항목 수
   * @return 채팅방 항목과 오프셋 페이지 정보
   */
  @GetMapping
  public ApiResponse<PageResponse<ChatRoomResponse>> listRooms(
      @AuthenticationPrincipal AuthPrincipal principal,
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "size", defaultValue = "20") int size) {
    // 조회 대상을 query나 body로 받지 않고 JWT의 userId로 고정해 다른 사용자의 목록을 열 수 없게 한다.
    return ApiResponse.success(chatRoomListService.listRooms(principal.userId(), page, size));
  }

  /**
   * 채팅방 화면의 헤더와 상대·매물 정보를 한 건 조회한다.
   *
   * <p>목록을 거치지 않는 알림·딥링크 진입에서도 사용할 수 있다. 메시지는 포함하지 않으며, 실제 대화는 {@code GET
   * /api/v1/chat-rooms/{roomId}/messages}로 별도 조회한다.
   *
   * @param principal 검증된 access token에서 만든 로그인 사용자 정보
   * @param roomId 목록·알림·딥링크에서 받은 서버 채팅방 ID
   * @return 현재 사용자가 볼 수 있는 채팅방 기본 정보
   */
  @GetMapping("/{roomId}")
  public ApiResponse<ChatRoomDetailResponse> getRoom(
      @AuthenticationPrincipal AuthPrincipal principal, @PathVariable Long roomId) {
    // userId를 요청으로 받지 않아 다른 사용자의 권한으로 단건 조회를 시도할 수 없게 한다.
    return ApiResponse.success(chatRoomDetailService.getRoom(principal.userId(), roomId));
  }

  /**
   * MySQL에 저장된 메시지를 과거 스크롤 또는 WebSocket 재연결 누락 보충 목적으로 조회한다.
   *
   * <p>{@code cursor}는 더 오래된 메시지, {@code afterMessageId}는 끊긴 동안 생긴 더 새 메시지를 찾는다. 두 모드는 동시에 쓰지 않으며 이
   * API는 전송 endpoint가 아니다.
   *
   * @param principal 검증된 access token에서 만든 로그인 사용자 정보
   * @param roomId 조회할 채팅방의 서버 식별자
   * @param cursor 이 메시지 ID보다 오래된 이력을 조회하는 커서
   * @param afterMessageId 이 메시지 ID보다 새로 저장된 누락분을 조회하는 기준
   * @param size 한 번에 조회할 메시지 수
   * @return 메시지와 다음 조회용 커서 정보
   */
  @GetMapping("/{roomId}/messages")
  public ApiResponse<CursorResponse<MessageResponse>> getMessages(
      @AuthenticationPrincipal AuthPrincipal principal,
      @PathVariable Long roomId,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "afterMessageId", required = false) String afterMessageId,
      @RequestParam(name = "size", defaultValue = "30") int size) {
    // 다른 사용자의 ID를 요청으로 받지 않고 JWT의 userId로 고정해 참여자 권한과 개인 삭제 경계를 확인한다.
    return ApiResponse.success(
        chatMessageHistoryService.getMessages(
            principal.userId(), roomId, cursor, afterMessageId, size));
  }

  /**
   * 요청한 사용자에게만 채팅방과 삭제 시점까지의 메시지를 숨긴다.
   *
   * <p>상대방의 채팅방과 과거 대화는 그대로 유지한다. 성공하면 앱은 응답 JSON을 기다리지 않고 해당 채팅방을 목록에서 제거하면 된다. 이미 숨긴 방에 같은 요청이 다시
   * 와도 같은 {@code 204 No Content}로 성공한다.
   *
   * @param principal 검증된 access token에서 만든 삭제 요청자 정보
   * @param roomId 목록·상세에서 받은 서버 채팅방 ID
   */
  @DeleteMapping("/{roomId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteRoom(
      @AuthenticationPrincipal AuthPrincipal principal, @PathVariable Long roomId) {
    // userId를 body나 query로 받지 않아 사용자가 상대방의 참여자 상태를 대신 삭제할 수 없게 한다.
    chatRoomDeletionService.deleteRoom(principal.userId(), roomId);
  }

  /**
   * 현재 채팅방의 두 참여자 중 로그인 사용자가 아닌 상대방을 차단한다.
   *
   * <p>프런트는 상대 사용자 ID나 요청 body를 보내지 않는다. 성공해도 기존 채팅방과 과거 메시지는 그대로 유지되므로 앱은 입력창만 비활성화하면 된다. 채팅방까지
   * 목록에서 숨기려면 삭제 API를 별도로 호출한다.
   *
   * @param principal 검증된 access token에서 만든 차단 요청자 정보
   * @param roomId 목록·상세에서 받은 서버 채팅방 ID
   */
  @PostMapping("/{roomId}/block")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void blockCounterpart(
      @AuthenticationPrincipal AuthPrincipal principal, @PathVariable Long roomId) {
    // 차단 대상을 body로 받지 않고 서비스가 참여자 중 상대방을 찾도록 해 임의 사용자 차단을 막는다.
    chatRoomBlockService.blockCounterpart(principal.userId(), roomId);
  }
}
