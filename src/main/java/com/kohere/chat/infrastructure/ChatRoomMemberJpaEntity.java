package com.kohere.chat.infrastructure;

import com.kohere.chat.domain.ChatParticipantRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * {@code chat_room_members} 전용 JPA 엔티티다.
 *
 * <p>방 자체와 메시지는 두 사용자가 공유하지만 목록 숨김과 과거 이력 경계는 사용자별 값이다. 따라서 채팅방 한 개에 임차인·임대인 두 행을 만들고 한쪽 행만 변경해 상대방
 * 화면을 보존한다.
 */
@Entity
@Table(name = "chat_room_members")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
class ChatRoomMemberJpaEntity {

  /** 사용자별 방 상태 행의 DB 식별자다. */
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** chat_rooms.id의 값 참조이며 삭제 순서와 복구 정책을 애플리케이션이 통제하도록 FK를 만들지 않는다. */
  @Column(nullable = false)
  private Long chatRoomId;

  /** 이 표시 상태를 소유하는 users.id 값이다. */
  @Column(nullable = false)
  private Long userId;

  /** 1:1 상대 users.id를 중복 저장해 차단·신고 대상 도출 시 역할 분기를 반복하지 않게 한다. */
  @Column(nullable = false)
  private Long counterpartId;

  /** 역할 이름을 문자열로 저장해 앱의 TENANT/LANDLORD 계약과 맞춘다. */
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private ChatParticipantRole memberRole;

  /** null이면 목록에 보이고 값이 있으면 현재 이 사용자에게만 숨긴 상태다. */
  private Instant roomHiddenAt;

  /** 방이 다시 표시돼도 이 messageId 이하의 과거 이력은 반환하지 않으며 0은 숨김 경계가 없다는 뜻이다. */
  @Column(nullable = false)
  private long historyHiddenThroughMessageId;

  /** 후속 보존 정책의 기준이 되는 최근 삭제 요청 시각이며 재표시와 별개로 보존한다. */
  private Instant deleteRequestedAt;

  /** 참여자 행을 처음 만든 서버 UTC 시각이다. */
  @Column(nullable = false)
  private Instant createdAt;

  /** 숨김 또는 재표시 상태를 마지막으로 변경한 서버 UTC 시각이다. */
  @Column(nullable = false)
  private Instant updatedAt;
}
