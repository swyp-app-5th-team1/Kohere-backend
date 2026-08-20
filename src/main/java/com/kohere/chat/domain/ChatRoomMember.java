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
   * 사용자가 채팅방을 삭제했을 때 이 사용자에게만 방과 현재까지의 대화를 숨긴다.
   *
   * <p>공유 채팅방과 메시지를 지우지 않고 참여자 한 명의 상태만 변경한다. 이미 숨긴 방에 같은 DELETE 요청이 다시 오면 최초 삭제 시각과 경계를 연장하지 않고 현재
   * 객체를 그대로 반환한다. 방이 다시 표시된 뒤 재삭제하는 경우에는 이전 경계와 현재 마지막 메시지 중 큰 값을 사용하므로 한 번 숨긴 과거 메시지가 다시 노출되지 않는다.
   *
   * @param lastMessageId 삭제 트랜잭션에서 잠근 채팅방의 현재 마지막 메시지 ID. 빈 방이면 {@code null}
   * @param now 실제 삭제 요청을 처리한 서버 UTC 시각
   * @return 새 숨김 상태 또는 이미 숨겨져 있으면 현재 객체
   */
  public ChatRoomMember hide(Long lastMessageId, Instant now) {
    if (roomHiddenAt != null) {
      return this;
    }

    // 빈 방은 0을 사용한다. 이전 삭제 경계보다 낮아지지 않게 해야 재진입 뒤에도 과거 이력이 복원되지 않는다.
    long currentLastMessageId = lastMessageId == null ? 0L : lastMessageId;
    long hiddenThrough = Math.max(historyHiddenThroughMessageId, currentLastMessageId);

    return toBuilder()
        .roomHiddenAt(now)
        .historyHiddenThroughMessageId(hiddenThrough)
        .deleteRequestedAt(now)
        .updatedAt(now)
        .build();
  }

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

  /**
   * 사용자 삭제 이후에 실제로 발생한 새 메시지가 있으면 채팅방을 목록에 다시 표시한다.
   *
   * <p>비동기 신청 이벤트가 늦게 처리될 수 있으므로 단순히 "현재 숨김"만 보고 되살리면 안 된다. 사용자가 메시지 발생 뒤에 방을 삭제했다면 그 최신 선택을 존중하고,
   * 메시지가 삭제 시각과 같거나 더 늦게 발생한 경우에만 방을 다시 표시한다. 과거 메시지 숨김 경계는 그대로 유지한다.
   *
   * @param activityOccurredAt 예약 신청 등 새 활동이 실제 발생한 시각
   * @param now 서버가 재표시 상태를 저장하는 시각
   * @return 재표시가 필요하면 새 참여자 상태, 아니면 현재 객체
   */
  public ChatRoomMember showForNewActivity(Instant activityOccurredAt, Instant now) {
    if (roomHiddenAt == null || roomHiddenAt.isAfter(activityOccurredAt)) {
      return this;
    }
    return showAgain(now);
  }
}
