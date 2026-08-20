package com.kohere.chat.infrastructure.websocket;

import com.kohere.chat.domain.ChatRoom;
import com.kohere.chat.domain.ChatRoomMember;
import com.kohere.chat.domain.ChatRoomMemberRepository;
import com.kohere.chat.domain.ChatRoomRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로그인 사용자가 특정 채팅방의 실시간 topic을 구독할 수 있는지 DB 기준으로 확인한다.
 *
 * <p>roomId는 순차적인 서버 번호라 다른 사용자가 추측할 수 있다. 따라서 주소 모양만 맞는다고 구독시키지 않고, 사용자의 참여자 행과 현재 표시 상태를 매번 확인한다.
 * 방이 없거나 참여자가 아니거나 사용자가 삭제해 숨긴 방인 경우는 모두 같은 거부 결과로 처리해 방 존재 여부를 노출하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class ChatStompAccessService {

  private final ChatRoomRepository chatRoomRepository;
  private final ChatRoomMemberRepository memberRepository;

  /**
   * 방 구독 권한을 확인하고 사용자가 REST로 보충해야 할 마지막 메시지 번호를 계산한다.
   *
   * <p>채팅방 전체의 마지막 메시지가 사용자의 과거 숨김 경계보다 작거나 같으면 그 메시지는 이 사용자에게 보여 주면 안 된다. 이때는 빈 방처럼 {@code null}을
   * 반환한다. 경계보다 큰 메시지가 있으면 해당 번호까지 REST 누락 조회를 완료한 뒤 실시간 수신과 합칠 수 있다.
   *
   * @param userId STOMP CONNECT JWT에서 확인한 {@code users.id}
   * @param roomId 구독하려는 {@code chat_rooms.id}
   * @return 사용자에게 보이는 마지막 messageId, 없으면 {@code null}
   * @throws AccessDeniedException 방이 없거나 현재 사용자가 볼 수 없는 경우
   */
  @Transactional(readOnly = true)
  public Long authorizeAndGetVisibleHighWatermark(long userId, long roomId) {
    ChatRoomMember member =
        memberRepository
            .findByChatRoomIdAndUserId(roomId, userId)
            .orElseThrow(ChatStompAccessService::accessDenied);

    // 사용자가 삭제한 채팅방은 직접 문의나 실제 새 메시지로 다시 표시되기 전까지 구독할 수 없다.
    if (member.getRoomHiddenAt() != null) {
      throw accessDenied();
    }

    ChatRoom room =
        chatRoomRepository.findById(roomId).orElseThrow(ChatStompAccessService::accessDenied);
    Long lastMessageId = room.getLastMessageId();

    // 방은 보이지만 삭제 이전 메시지만 존재할 수 있다. 그런 메시지는 high-watermark로도 노출하지 않는다.
    if (lastMessageId == null || lastMessageId <= member.getHistoryHiddenThroughMessageId()) {
      return null;
    }
    return lastMessageId;
  }

  /** 외부에는 존재·비참여·숨김 중 어느 조건이 실패했는지 구분하지 않는 공통 예외를 만든다. */
  private static AccessDeniedException accessDenied() {
    return new AccessDeniedException("Chat room subscription is not allowed");
  }
}
