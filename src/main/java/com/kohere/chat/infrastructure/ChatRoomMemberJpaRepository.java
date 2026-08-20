package com.kohere.chat.infrastructure;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data가 {@code chat_room_members} SQL을 생성하게 하는 내부 저장소다. */
interface ChatRoomMemberJpaRepository extends JpaRepository<ChatRoomMemberJpaEntity, Long> {

  /**
   * 로그인 사용자가 방 참여자인지와 개인 가시성 상태를 한 번에 읽는다.
   *
   * @param chatRoomId 방 번호
   * @param userId 로그인 사용자 번호
   * @return 방·사용자 조합의 참여자 엔티티
   */
  Optional<ChatRoomMemberJpaEntity> findByChatRoomIdAndUserId(Long chatRoomId, Long userId);

  /**
   * 방의 참여자 두 행을 안정적인 생성 순서로 읽는다.
   *
   * @param chatRoomId 방 번호
   * @return ID 오름차순 참여자 엔티티
   */
  List<ChatRoomMemberJpaEntity> findByChatRoomIdOrderByIdAsc(Long chatRoomId);
}
