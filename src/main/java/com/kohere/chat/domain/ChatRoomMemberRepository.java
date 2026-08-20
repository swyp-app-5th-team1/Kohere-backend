package com.kohere.chat.domain;

import java.util.List;
import java.util.Optional;

/**
 * 참여자별 채팅방 역할·가시성 상태의 영속 포트다.
 *
 * <p>공유 방과 메시지는 한 벌만 저장하지만 숨김 여부와 과거 이력 경계는 사용자마다 다르다. 이 포트가 그 개인 상태를 별도 행으로 저장함으로써 한쪽 삭제가 상대방 데이터에
 * 영향을 주지 않게 한다.
 */
public interface ChatRoomMemberRepository {

  /**
   * 참여자 한 명의 역할과 표시 상태를 저장하거나 갱신한다.
   *
   * @param member 저장할 사용자별 방 상태
   * @return DB가 발급한 ID와 저장값을 포함한 방 상태
   */
  ChatRoomMember save(ChatRoomMember member);

  /**
   * 새 방을 만들 때 임차인·임대인 두 행을 함께 저장한다.
   *
   * <p>원자성은 이 메서드 하나가 아니라 호출하는 애플리케이션 서비스의 트랜잭션이 보장한다.
   *
   * @param members 같은 채팅방의 참여자 상태 목록
   * @return 저장된 참여자 상태 목록
   */
  List<ChatRoomMember> saveAll(List<ChatRoomMember> members);

  /**
   * 방 번호와 로그인 사용자 번호로 해당 사용자의 역할·가시성 상태를 찾는다.
   *
   * @param chatRoomId 확인할 방 번호
   * @param userId 로그인 사용자 번호
   * @return 참여 중이면 사용자별 방 상태, 아니면 빈 값
   */
  Optional<ChatRoomMember> findByChatRoomIdAndUserId(Long chatRoomId, Long userId);

  /**
   * 한 채팅방의 두 참여자 상태를 조회한다.
   *
   * @param chatRoomId 조회할 방 번호
   * @return member ID 오름차순의 참여자 상태 목록
   */
  List<ChatRoomMember> findByChatRoomId(Long chatRoomId);
}
