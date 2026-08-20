package com.kohere.chat.application;

import com.kohere.chat.domain.ChatCategory;
import com.kohere.chat.domain.ChatParticipantRole;
import com.kohere.chat.domain.ChatRoom;
import com.kohere.chat.domain.ChatRoomMember;
import com.kohere.chat.domain.ChatRoomMemberRepository;
import com.kohere.chat.domain.ChatRoomRepository;
import com.kohere.chat.domain.ListingSnapshot;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 채팅방 한 행과 임차인·임대인 참여자 두 행을 같은 MySQL 트랜잭션으로 저장한다.
 *
 * <p>별도 컴포넌트로 분리한 이유는 {@link ChatService}가 동시 생성 UNIQUE 충돌을 트랜잭션 밖에서 처리할 수 있게 하기 위해서다. 실패한 JPA 트랜잭션
 * 안에서 기존 방을 다시 조회하면 rollback-only 상태 때문에 조회 결과를 안전하게 사용할 수 없다.
 */
@Component
@RequiredArgsConstructor
public class ChatRoomCreator {

  private final ChatRoomRepository chatRoomRepository;
  private final ChatRoomMemberRepository memberRepository;

  /**
   * 새 채팅방과 정확히 두 명의 참여자를 원자적으로 저장한다.
   *
   * <p>방 저장이나 두 참여자 저장 중 하나라도 실패하면 {@link Transactional} 경계가 전체 작업을 롤백한다. 따라서 참여자가 한 명뿐인 불완전한 방은 남지
   * 않는다.
   *
   * @param seed 검증이 끝난 매물의 실제 임대인과 표시 정보
   * @param tenantId JWT에서 얻은 임차인 사용자 ID
   * @param now 방과 참여자의 공통 생성 시각
   * @return DB가 발급한 ID를 포함한 새 채팅방
   */
  @Transactional
  public ChatRoom create(ChatRoomSeed seed, long tenantId, Instant now) {
    ChatRoom room =
        chatRoomRepository.save(
            ChatRoom.builder()
                .listingId(seed.listingId())
                .tenantId(tenantId)
                .landlordId(seed.landlordId())
                .category(ChatCategory.LANDLORD)
                // 매물이 나중에 비공개·삭제돼도 기존 방 제목과 주소를 표시할 수 있도록 생성 시점 값을 보존한다.
                .listingSnapshot(new ListingSnapshot(seed.title(), seed.address()))
                .createdAt(now)
                .updatedAt(now)
                .build());

    // 참여자 상태는 사용자별 숨김·삭제 경계를 독립적으로 관리해야 하므로 방 행과 별도로 정확히 두 행을 만든다.
    memberRepository.saveAll(
        List.of(
            newMember(room.getId(), tenantId, seed.landlordId(), ChatParticipantRole.TENANT, now),
            newMember(
                room.getId(), seed.landlordId(), tenantId, ChatParticipantRole.LANDLORD, now)));
    return room;
  }

  /**
   * 직접 문의로 기존 방에 재진입한 임차인의 목록 표시 상태만 되돌린다.
   *
   * <p>일반 사용자의 삭제 복원 기능은 제공하지 않으므로 과거 메시지 숨김 경계는 변경하지 않는다. 이미 보이는 상태에서는 DB UPDATE도 하지 않는다.
   *
   * @param roomId 다시 표시할 기존 채팅방 ID
   * @param tenantId 재진입한 임차인 ID
   * @param now 재진입 시각
   */
  @Transactional
  public void showExistingRoomForTenant(long roomId, long tenantId, Instant now) {
    // 삭제와 같은 room -> member 순서로 잠가 동시 DELETE와 직접 문의 중 마지막으로 실행된 행동이 일관되게 반영되게 한다.
    chatRoomRepository
        .findByIdForUpdate(roomId)
        .orElseThrow(() -> new IllegalStateException("재진입할 채팅방을 찾을 수 없습니다."));

    List<ChatRoomMember> members = memberRepository.findByChatRoomIdForUpdate(roomId);
    if (members.size() != 2) {
      throw new IllegalStateException("1:1 채팅방에는 참여자가 정확히 두 명이어야 합니다.");
    }

    ChatRoomMember current =
        members.stream()
            .filter(member -> member.getUserId() == tenantId)
            .findFirst()
            // 방은 있는데 임차인 행이 없으면 저장 불변식이 깨진 서버 상태다. 사용자 입력 오류로 감추지 않고 즉시 드러낸다.
            .orElseThrow(() -> new IllegalStateException("채팅방의 임차인 참여자 정보를 찾을 수 없습니다."));

    ChatRoomMember visible = current.showAgain(now);
    if (visible != current) {
      memberRepository.save(visible);
    }
  }

  /** 역할과 상대가 서로 뒤바뀌지 않도록 신규 참여자 생성 코드를 한곳에 둔다. */
  private static ChatRoomMember newMember(
      long roomId, long userId, long counterpartId, ChatParticipantRole role, Instant now) {
    return ChatRoomMember.builder()
        .chatRoomId(roomId)
        .userId(userId)
        .counterpartId(counterpartId)
        .role(role)
        .historyHiddenThroughMessageId(0L)
        .createdAt(now)
        .updatedAt(now)
        .build();
  }
}
