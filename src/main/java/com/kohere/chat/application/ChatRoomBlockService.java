package com.kohere.chat.application;

import com.kohere.chat.domain.ChatRoomMember;
import com.kohere.chat.domain.ChatRoomMemberRepository;
import com.kohere.chat.domain.ChatRoomNotFoundException;
import com.kohere.chat.domain.ChatRoomRepository;
import com.kohere.user.api.UserBlockService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 채팅방의 두 참여자 중 로그인 사용자가 아닌 상대방을 찾아 기존 사용자 차단 기능에 연결한다.
 *
 * <p>프런트는 {@code roomId}만 보내고 차단할 {@code userId}는 보내지 않는다. 서버가 실제 참여자 두 명을 확인한 뒤 상대를 선택하므로 사용자가 요청
 * 값을 바꿔 채팅방과 관계없는 사람을 차단하는 일을 막을 수 있다. 차단 관계 자체는 user 모듈과 {@code user_blocks}가 계속 소유한다.
 */
@Service
@RequiredArgsConstructor
public class ChatRoomBlockService {

  private final ChatRoomRepository chatRoomRepository;
  private final ChatRoomMemberRepository memberRepository;
  private final UserBlockService userBlockService;

  /**
   * 현재 채팅방의 상대방을 차단한다.
   *
   * <p>TEXT 저장도 같은 채팅방 행을 먼저 잠근다. 따라서 같은 방에서 메시지 저장과 차단이 동시에 시작돼도 먼저 잠금을 얻은 작업이 끝난 뒤 다음 작업이 실행된다.
   * 차단이 먼저 완료되면 뒤의 TEXT 저장은 기존 양방향 차단 검사에서 거부된다.
   *
   * @param blockerId JWT에서 확인한 차단 요청자의 {@code users.id}
   * @param roomId 채팅방 목록·상세에서 받은 서버 채팅방 ID
   * @throws ChatRoomNotFoundException 방이 없거나 요청자가 참여자가 아닌 경우
   */
  @Transactional
  public void blockCounterpart(long blockerId, long roomId) {
    // 방 존재 확인과 동시 TEXT 저장의 순서를 한곳에서 정하기 위해 메시지 저장과 같은 room 잠금을 사용한다.
    chatRoomRepository.findByIdForUpdate(roomId).orElseThrow(ChatRoomNotFoundException::new);

    // 참여자 두 행도 항상 ID 오름차순으로 잠가 삭제·재진입·차단 작업끼리 잠금 순서가 달라지지 않게 한다.
    List<ChatRoomMember> members = memberRepository.findByChatRoomIdForUpdate(roomId);
    if (members.size() != 2) {
      // 1:1 방의 내부 데이터가 깨졌다면 임의의 상대를 고르지 않고 전체 트랜잭션을 실패시킨다.
      throw new IllegalStateException("1:1 채팅방에는 참여자가 정확히 두 명이어야 합니다.");
    }

    ChatRoomMember requester = findMember(members, blockerId);
    if (requester == null) {
      // 방 부재와 비참여자를 같은 404로 처리해 제3자가 roomId 존재 여부를 추측하지 못하게 한다.
      throw new ChatRoomNotFoundException();
    }

    ChatRoomMember counterpart = findCounterpart(members, blockerId);
    if (counterpart == null) {
      throw new IllegalStateException("1:1 채팅방의 상대 참여자를 찾을 수 없습니다.");
    }

    // 기존 UserBlockService가 UNIQUE 경합과 반복 호출을 멱등 처리하므로 채팅 모듈은 별도 차단 테이블을 만들지 않는다.
    userBlockService.block(requester.getUserId(), counterpart.getUserId());
  }

  /** 참여자 두 명 중 로그인 사용자와 같은 행을 찾는다. */
  private static ChatRoomMember findMember(List<ChatRoomMember> members, long userId) {
    return members.stream().filter(member -> member.getUserId() == userId).findFirst().orElse(null);
  }

  /** 참여자 두 명 중 로그인 사용자가 아닌 상대방 행을 찾는다. */
  private static ChatRoomMember findCounterpart(List<ChatRoomMember> members, long blockerId) {
    return members.stream()
        .filter(member -> member.getUserId() != blockerId)
        .findFirst()
        .orElse(null);
  }
}
