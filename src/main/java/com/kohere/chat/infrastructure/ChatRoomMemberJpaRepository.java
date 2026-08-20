package com.kohere.chat.infrastructure;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

  /**
   * 사용자별 숨김 상태는 member에서, 최근 활동 정렬값은 room에서 가져오는 목록 전용 조인 쿼리다.
   *
   * <p>같은 시각의 행은 roomId 내림차순으로 한 번 더 정렬해 페이지를 넘길 때 항목 순서가 흔들리지 않게 한다. countQuery도 같은 가시성 조건을 사용해
   * {@code totalElements}가 실제 목록과 일치한다.
   */
  @Query(
      value =
          """
          SELECT member
          FROM ChatRoomMemberJpaEntity member, ChatRoomJpaEntity room
          WHERE member.chatRoomId = room.id
            AND member.userId = :userId
            AND member.roomHiddenAt IS NULL
          ORDER BY COALESCE(room.lastMessageAt, room.createdAt) DESC, room.id DESC
          """,
      countQuery =
          """
          SELECT COUNT(member)
          FROM ChatRoomMemberJpaEntity member, ChatRoomJpaEntity room
          WHERE member.chatRoomId = room.id
            AND member.userId = :userId
            AND member.roomHiddenAt IS NULL
          """)
  Page<ChatRoomMemberJpaEntity> findVisiblePageByUserId(
      @Param("userId") Long userId, Pageable pageable);
}
