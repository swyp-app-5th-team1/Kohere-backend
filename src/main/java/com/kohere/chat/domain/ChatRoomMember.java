package com.kohere.chat.domain;

import java.time.Instant;
import lombok.Builder;
import lombok.Getter;

/**
 * 공유 채팅방에 대한 사용자 한 명의 역할과 표시 상태다.
 *
 * <p>한쪽 사용자가 채팅방을 삭제해도 상대방 화면은 유지되어야 하므로 삭제 상태를 {@link ChatRoom}에 두지 않고 참여자별 행으로 분리한다. {@code
 * roomHiddenAt}은 목록 표시 여부를, {@code historyHiddenThroughMessageId}는 다시 방이 표시됐을 때도 복원하지 않을 과거 이력의 상한을
 * 나타낸다. 일반 사용자용 Undo나 복원 API는 제공하지 않는다.
 */
@Getter
@Builder(toBuilder = true)
public class ChatRoomMember {

  /** DB가 발급하는 참여자 상태 행 번호이며 신규 저장 전에는 {@code null}이다. */
  private final Long id;

  /** 참여 중인 {@code chat_rooms.id} 값이다. */
  private final Long chatRoomId;

  /** 이 표시 상태의 소유자인 {@code users.id} 값이다. */
  private final Long userId;

  /** 1:1 방의 다른 참여자 {@code users.id} 값으로 차단·신고 대상 도출에 사용한다. */
  private final Long counterpartId;

  /** 이 방에서 사용자가 임차인인지 임대인인지 나타낸다. */
  private final ChatParticipantRole role;

  /** 현재 채팅방을 이 사용자의 목록에서 숨긴 시각이며 보이는 상태에서는 {@code null}이다. */
  private final Instant roomHiddenAt;

  /** 이 사용자의 메시지 조회에서 제외할 마지막 messageId이며 0은 아직 숨긴 이력이 없다는 뜻이다. */
  private final long historyHiddenThroughMessageId;

  /** 가장 최근의 실제 삭제 요청 시각이며 방이 새 메시지로 다시 표시돼도 보존한다. */
  private final Instant deleteRequestedAt;

  /** 서버가 참여자 행을 처음 저장한 UTC 시각이다. */
  private final Instant createdAt;

  /** 이 사용자의 숨김·재표시 상태를 마지막으로 변경한 UTC 시각이다. */
  private final Instant updatedAt;

  /**
   * 사용자가 같은 매물에서 직접 문의해 기존 방으로 다시 들어올 때 목록에 방을 표시한다.
   *
   * <p>이 동작은 삭제 복원이 아니다. {@code historyHiddenThroughMessageId}와 {@code deleteRequestedAt}은 그대로 두고
   * 현재 목록 표시 여부만 되돌린다. 이미 보이는 방이면 새 객체나 불필요한 UPDATE를 만들지 않고 자기 자신을 반환한다.
   *
   * @param now 방을 다시 표시한 서버 UTC 시각
   * @return 표시 상태가 반영된 참여자 상태
   */
  public ChatRoomMember showAgain(Instant now) {
    if (roomHiddenAt == null) {
      return this;
    }
    return toBuilder().roomHiddenAt(null).updatedAt(now).build();
  }
}
