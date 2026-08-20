package com.kohere.chat.infrastructure;

import com.kohere.chat.domain.ChatRoomMember;
import com.kohere.chat.domain.ChatRoomMemberRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 사용자별 채팅방 상태 포트와 Spring Data JPA를 연결하는 영속 어댑터다.
 *
 * <p>채팅방 삭제·재표시 기능 자체는 후속 단계에서 구현하지만, 그 기능이 한쪽 참여자의 행만 변경할 수 있도록 지금부터 방과 가시성 상태를 분리해 저장한다.
 */
@Repository
@RequiredArgsConstructor
public class ChatRoomMemberRepositoryImpl implements ChatRoomMemberRepository {

  /** {@code chat_room_members} SQL 실행을 위임할 내부 저장소다. */
  private final ChatRoomMemberJpaRepository jpaRepository;

  /** {@inheritDoc} */
  @Override
  public ChatRoomMember save(ChatRoomMember member) {
    return toDomain(jpaRepository.save(toEntity(member)));
  }

  /** {@inheritDoc} */
  @Override
  public List<ChatRoomMember> saveAll(List<ChatRoomMember> members) {
    // 두 참여자를 한 번에 JPA에 전달하지만 트랜잭션 경계는 후속 ChatService가 소유해야 둘 중 하나만 남는 부분 성공을 막을 수 있다.
    return jpaRepository
        .saveAll(members.stream().map(ChatRoomMemberRepositoryImpl::toEntity).toList())
        .stream()
        .map(ChatRoomMemberRepositoryImpl::toDomain)
        .toList();
  }

  /** {@inheritDoc} */
  @Override
  public Optional<ChatRoomMember> findByChatRoomIdAndUserId(Long chatRoomId, Long userId) {
    return jpaRepository
        .findByChatRoomIdAndUserId(chatRoomId, userId)
        .map(ChatRoomMemberRepositoryImpl::toDomain);
  }

  /** {@inheritDoc} */
  @Override
  public List<ChatRoomMember> findByChatRoomId(Long chatRoomId) {
    return jpaRepository.findByChatRoomIdOrderByIdAsc(chatRoomId).stream()
        .map(ChatRoomMemberRepositoryImpl::toDomain)
        .toList();
  }

  /**
   * JPA 참여자 엔티티를 사용자별 방 상태 도메인 객체로 복원한다.
   *
   * @param entity MySQL에서 읽은 참여자 엔티티
   * @return 순수 도메인 참여자 상태
   */
  private static ChatRoomMember toDomain(ChatRoomMemberJpaEntity entity) {
    return ChatRoomMember.builder()
        .id(entity.getId())
        .chatRoomId(entity.getChatRoomId())
        .userId(entity.getUserId())
        .counterpartId(entity.getCounterpartId())
        .role(entity.getMemberRole())
        .roomHiddenAt(entity.getRoomHiddenAt())
        .historyHiddenThroughMessageId(entity.getHistoryHiddenThroughMessageId())
        .deleteRequestedAt(entity.getDeleteRequestedAt())
        .createdAt(entity.getCreatedAt())
        .updatedAt(entity.getUpdatedAt())
        .build();
  }

  /**
   * 사용자별 방 상태를 {@code chat_room_members} JPA 엔티티로 변환한다.
   *
   * @param domain 저장할 참여자 상태
   * @return Hibernate가 관리할 참여자 엔티티
   */
  private static ChatRoomMemberJpaEntity toEntity(ChatRoomMember domain) {
    return ChatRoomMemberJpaEntity.builder()
        .id(domain.getId())
        .chatRoomId(domain.getChatRoomId())
        .userId(domain.getUserId())
        .counterpartId(domain.getCounterpartId())
        .memberRole(domain.getRole())
        .roomHiddenAt(domain.getRoomHiddenAt())
        .historyHiddenThroughMessageId(domain.getHistoryHiddenThroughMessageId())
        .deleteRequestedAt(domain.getDeleteRequestedAt())
        .createdAt(domain.getCreatedAt())
        .updatedAt(domain.getUpdatedAt())
        .build();
  }
}
