package com.kohere.report.domain;

import java.time.Instant;

/**
 * 신고 당시 증거에 포함한 TEXT 원문 한 건이다.
 *
 * <p>번역문은 사용자별 표시용 파생 데이터라 넣지 않는다. {@code originalContent}를 저장해야 후속 관리자가 동일한 정본으로 판단할 수 있다.
 */
public record ReportEvidenceMessage(
    Long messageId, Long senderId, String originalContent, Instant sentAt) {

  /** JSON 스냅샷에 불완전한 메시지가 들어가지 않도록 생성 즉시 확인한다. */
  public ReportEvidenceMessage {
    if (messageId == null || senderId == null || originalContent == null || sentAt == null) {
      throw new IllegalArgumentException("report evidence message fields are required");
    }
  }
}
