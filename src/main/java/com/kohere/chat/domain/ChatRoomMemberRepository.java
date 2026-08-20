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

  /**
   * 채팅방 상태를 바꾸는 트랜잭션에서 두 참여자 행을 ID 순서로 잠가 조회한다.
   *
   * <p>삭제·직접 문의 재진입이 동시에 실행돼도 모두 {@code room -> member ID 오름차순}으로 잠그면 서로 반대 순서로 기다리는 교착을 피하고, 마지막으로
   * 잠금을 얻은 사용자 행동이 일관되게 반영된다. 단순 조회 API에서는 사용하지 않는다.
   *
   * @param chatRoomId 참여자 행을 잠글 채팅방 ID
   * @return 잠긴 참여자 상태 목록
   */
  List<ChatRoomMember> findByChatRoomIdForUpdate(Long chatRoomId);

  /**
   * 로그인 사용자의 화면에 현재 보이는 채팅방 상태를 최근 활동 순으로 조회한다.
   *
   * <p>{@code roomHiddenAt != null}인 행은 사용자가 삭제해 목록에서 숨긴 상태이므로 제외한다. 정렬은 연결된 채팅방의 마지막 메시지 시각을 사용하고,
   * 메시지가 없는 방은 생성 시각을 사용한다.
   *
   * @param userId JWT에서 얻은 로그인 사용자 ID
   * @param page 0부터 시작하는 페이지 번호
   * @param size 한 페이지 크기
   * @return 정렬된 참여자 상태와 전체 개수
   */
  ChatRoomMemberPage findVisiblePageByUserId(Long userId, int page, int size);
}
