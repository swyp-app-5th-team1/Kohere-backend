package com.kohere.chat.application;

import com.kohere.chat.domain.ChatRoom;
import com.kohere.chat.domain.ChatRoomMember;
import com.kohere.chat.domain.ChatRoomMemberRepository;
import com.kohere.chat.domain.ChatRoomNotFoundException;
import com.kohere.chat.domain.ChatRoomRepository;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공유 채팅방을 지우지 않고 로그인 사용자에게만 채팅방과 현재까지의 메시지를 숨긴다.
 *
 * <p>한쪽 사용자의 삭제가 상대방 기록에 영향을 주면 안 되므로 {@code chat_rooms}나 {@code chat_messages}를 삭제하지 않는다. 대신 요청자의
 * {@code chat_room_members} 한 행에 숨김 시각과 메시지 경계를 기록한다. 일반 사용자의 복원 API는 제공하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class ChatRoomDeletionService {

  private final ChatRoomRepository chatRoomRepository;
  private final AppUserGuard appUserGuard;
  private final ChatRoomMemberRepository memberRepository;

  /**
   * 요청자에게만 채팅방을 숨기고 삭제 시점까지의 메시지를 이후 조회에서도 제외한다.
   *
   * <p>방을 먼저 잠그면 동시에 들어온 새 메시지와 삭제의 순서가 명확해진다. 메시지가 먼저 저장되면 그 ID까지 숨기고, 삭제가 먼저 완료되면 이후 새 메시지는 더 큰
   * ID로 저장되어 방을 다시 표시할 수 있다. 두 참여자도 ID 순서로 잠가 직접 문의 재진입과의 교착을 피한다.
   *
   * @param userId JWT에서 확인한 삭제 요청자의 {@code users.id}
   * @param roomId 앱의 채팅방 목록·상세에서 받은 서버 채팅방 ID
   * @throws ChatRoomNotFoundException 방이 없거나 요청자가 참여자가 아닌 경우
   */
  @Transactional
  public void deleteRoom(long userId, long roomId) {
    appUserGuard.requireAppUser(userId);
    ChatRoom room =
        chatRoomRepository.findByIdForUpdate(roomId).orElseThrow(ChatRoomNotFoundException::new);

    List<ChatRoomMember> members = memberRepository.findByChatRoomIdForUpdate(roomId);
    if (members.size() != 2) {
      // 1:1 방의 내부 데이터가 깨진 상태에서 일부 행만 변경하지 않고 전체 트랜잭션을 실패시킨다.
      throw new IllegalStateException("1:1 채팅방에는 참여자가 정확히 두 명이어야 합니다.");
    }

    ChatRoomMember requester =
        members.stream()
            .filter(member -> member.getUserId() == userId)
            .findFirst()
            // 방 부재와 비참여자를 같은 404로 처리해 제3자가 roomId 존재 여부를 추측하지 못하게 한다.
            .orElseThrow(ChatRoomNotFoundException::new);

    ChatRoomMember hidden = requester.hide(room.getLastMessageId(), Instant.now());
    if (hidden != requester) {
      // 상대 member는 저장하지 않는다. 한쪽 삭제가 상대방 목록과 과거 이력을 바꾸지 않게 하는 핵심 경계다.
      memberRepository.save(hidden);
    }
  }
}
