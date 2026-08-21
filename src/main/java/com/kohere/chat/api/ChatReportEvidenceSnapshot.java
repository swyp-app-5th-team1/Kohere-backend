package com.kohere.chat.api;

import java.time.Instant;
import java.util.List;

/**
 * 채팅방 신고 접수에 필요한 참여자·매물 문맥과 최근 원문 메시지 묶음이다.
 *
 * <p>이 record는 모듈 사이 전달용이다. report 모듈은 받은 값을 자기 증거 스냅샷으로 복사해 정해진 보관기간 동안 독립적으로 관리한다.
 */
public record ChatReportEvidenceSnapshot(
    Long chatRoomId,
    String listingId,
    Long reporterId,
    Long reportedUserId,
    Long evidenceThroughMessageId,
    List<ChatReportMessageSnapshot> messages,
    Instant capturedAt) {

  /** 호출 뒤 원본 목록이 바뀌지 않도록 공개 경계에서 방어 복사한다. */
  public ChatReportEvidenceSnapshot {
    messages = List.copyOf(messages);
  }
}
