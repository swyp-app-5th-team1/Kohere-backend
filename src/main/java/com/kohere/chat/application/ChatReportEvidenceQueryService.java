package com.kohere.chat.application;

import com.kohere.chat.api.ChatReportEvidenceProvider;
import com.kohere.chat.api.ChatReportEvidenceSnapshot;
import com.kohere.chat.api.ChatReportMessageSnapshot;
import com.kohere.chat.domain.ChatRoom;
import com.kohere.chat.domain.ChatRoomMember;
import com.kohere.chat.domain.ChatRoomMemberRepository;
import com.kohere.chat.domain.ChatRoomNotFoundException;
import com.kohere.chat.domain.ChatRoomRepository;
import com.kohere.chat.domain.Message;
import com.kohere.chat.domain.MessageRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 채팅방 신고에 사용할 최근 TEXT 원문을 안전하게 캡처한다.
 *
 * <p>신고 접수도 TEXT 저장·방 삭제와 같은 {@code room -> member} 잠금 순서를 사용한다. 따라서 동시에 새 메시지나 삭제가 들어와도 신고 증거 경계가
 * 중간 상태를 보지 않는다.
 */
@Service
@RequiredArgsConstructor
public class ChatReportEvidenceQueryService implements ChatReportEvidenceProvider {

  /** 한 신고에 보관하는 최근 TEXT 원문 최대 개수다. */
  static final int MAX_EVIDENCE_MESSAGE_COUNT = 20;

  private final ChatRoomRepository chatRoomRepository;
  private final ChatRoomMemberRepository memberRepository;
  private final MessageRepository messageRepository;

  /** {@inheritDoc} */
  @Override
  @Transactional
  public ChatReportEvidenceSnapshot capture(long reporterId, long chatRoomId) {
    // 방을 먼저 잠가 동시에 저장되는 새 메시지와 신고 증거의 전후 관계를 한 줄로 정한다.
    ChatRoom room =
        chatRoomRepository
            .findByIdForUpdate(chatRoomId)
            .orElseThrow(ChatRoomNotFoundException::new);

    // 삭제·차단과 같은 순서로 참여자 두 행을 잠가 사용자의 숨김 경계를 안정적으로 읽는다.
    List<ChatRoomMember> members = memberRepository.findByChatRoomIdForUpdate(chatRoomId);
    if (members.size() != 2) {
      throw new IllegalStateException("1:1 채팅방에는 참여자가 정확히 두 명이어야 합니다.");
    }

    ChatRoomMember reporter = findMember(members, reporterId);
    // 숨긴 방은 일반 채팅 화면에서 접근할 수 없다. 방 없음과 같은 404로 처리해 roomId 존재 여부도 노출하지 않는다.
    if (reporter == null || reporter.getRoomHiddenAt() != null) {
      throw new ChatRoomNotFoundException();
    }

    ChatRoomMember reported = findCounterpart(members, reporterId);
    if (reported == null) {
      throw new IllegalStateException("1:1 채팅방의 상대 참여자를 찾을 수 없습니다.");
    }

    // 사용자가 이전에 숨긴 messageId 이하의 과거 대화는 다시 표시된 방에서도 신고 증거로 되살리지 않는다.
    List<Message> newestFirst =
        messageRepository.findRecentTextForReport(
            chatRoomId, reporter.getHistoryHiddenThroughMessageId(), MAX_EVIDENCE_MESSAGE_COUNT);

    // 관리자 화면에서는 대화를 위에서 아래로 읽으므로 DB 최신순 결과를 시간축 오름차순으로 바꿔 전달한다.
    List<ChatReportMessageSnapshot> messages =
        newestFirst.stream()
            .sorted(Comparator.comparing(Message::getId))
            .map(ChatReportEvidenceQueryService::toSnapshot)
            .toList();

    Long evidenceThroughMessageId =
        messages.stream()
            .map(ChatReportMessageSnapshot::messageId)
            .max(Long::compareTo)
            .orElse(null);

    return new ChatReportEvidenceSnapshot(
        room.getId(),
        room.getListingId(),
        reporter.getUserId(),
        reported.getUserId(),
        evidenceThroughMessageId,
        messages,
        Instant.now());
  }

  /** 참여자 두 명 중 신고자 행을 찾는다. */
  private static ChatRoomMember findMember(List<ChatRoomMember> members, long userId) {
    return members.stream().filter(member -> member.getUserId() == userId).findFirst().orElse(null);
  }

  /** 참여자 두 명 중 신고자가 아닌 상대방 행을 찾는다. */
  private static ChatRoomMember findCounterpart(List<ChatRoomMember> members, long reporterId) {
    return members.stream()
        .filter(member -> member.getUserId() != reporterId)
        .findFirst()
        .orElse(null);
  }

  /** 채팅 도메인 메시지에서 report 모듈에 공개해도 되는 원문 필드만 복사한다. */
  private static ChatReportMessageSnapshot toSnapshot(Message message) {
    return new ChatReportMessageSnapshot(
        message.getId(), message.getSenderId(), message.getContent(), message.getSentAt());
  }
}
