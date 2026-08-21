package com.kohere.report.domain;

import java.util.List;

/**
 * 신고 접수 순간의 채팅방 문맥과 최근 TEXT 원문을 묶은 불변 JSON 스냅샷이다.
 *
 * <p>채팅 메시지가 후속 보존 정책으로 물리 삭제되더라도 신고 판단 근거는 정해진 보관기간 동안 유지되어야 하므로 필요한 값을 복사해 둔다.
 */
public record ReportEvidenceSnapshot(
    Long chatRoomId,
    String listingId,
    Long reporterId,
    Long reportedUserId,
    List<ReportEvidenceMessage> messages) {

  /** 호출자가 나중에 목록을 바꿔 저장된 증거 의미가 달라지지 않도록 방어 복사한다. */
  public ReportEvidenceSnapshot {
    if (chatRoomId == null
        || listingId == null
        || reporterId == null
        || reportedUserId == null
        || messages == null
        || messages.isEmpty()) {
      throw new IllegalArgumentException("report evidence snapshot fields are required");
    }
    messages = List.copyOf(messages);
  }
}
