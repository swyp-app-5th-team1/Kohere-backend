package com.kohere.report.domain;

import java.time.Instant;
import lombok.Builder;
import lombok.Getter;

/**
 * 사용자가 1:1 채팅방에서 상대방을 신고한 접수 기록이다.
 *
 * <p>이 객체에는 관리자 목록에서 자주 사용할 작은 정보만 둔다. 실제 대화 원문은 민감하고 크기가 크므로 {@link ReportEvidence}에 별도로 저장한다. JPA
 * 매핑은 infrastructure 계층이 담당해 이 도메인 객체는 저장 기술을 모른다.
 */
@Getter
public class Report {

  private final Long id;
  private final Long chatRoomId;
  private final Long reporterId;
  private final Long reportedUserId;
  private final ReportReason reason;
  private final ReportStatus status;
  private final Long evidenceThroughMessageId;
  private final Instant receivedAt;
  private final Instant retentionExpiresAt;
  private final Instant createdAt;
  private final Instant updatedAt;

  /**
   * 필수값과 신고자·신고 대상자 불변식을 생성 시점에 확인한다.
   *
   * <p>DB CHECK는 마지막 방어선이다. 도메인에서도 먼저 검사하면 잘못된 객체가 SQL까지 내려가 원인을 찾기 어려워지는 일을 막을 수 있다.
   */
  @Builder
  private Report(
      Long id,
      Long chatRoomId,
      Long reporterId,
      Long reportedUserId,
      ReportReason reason,
      ReportStatus status,
      Long evidenceThroughMessageId,
      Instant receivedAt,
      Instant retentionExpiresAt,
      Instant createdAt,
      Instant updatedAt) {
    this.id = id;
    this.chatRoomId = chatRoomId;
    this.reporterId = reporterId;
    this.reportedUserId = reportedUserId;
    this.reason = reason;
    this.status = status;
    this.evidenceThroughMessageId = evidenceThroughMessageId;
    this.receivedAt = receivedAt;
    this.retentionExpiresAt = retentionExpiresAt;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
    validate();
  }

  /** 채팅방 신고 한 건이 관리자 처리에 필요한 최소 정보를 모두 갖췄는지 확인한다. */
  private void validate() {
    if (chatRoomId == null
        || reporterId == null
        || reportedUserId == null
        || reason == null
        || status == null
        || evidenceThroughMessageId == null
        || receivedAt == null
        || retentionExpiresAt == null
        || createdAt == null
        || updatedAt == null) {
      throw new IllegalArgumentException("chat report fields are required");
    }
    if (reporterId.equals(reportedUserId)) {
      throw new IllegalArgumentException("reporter and reported user must be different");
    }
    if (evidenceThroughMessageId <= 0) {
      throw new IllegalArgumentException("evidence message ID must be positive");
    }
    if (!retentionExpiresAt.isAfter(receivedAt)) {
      throw new IllegalArgumentException("retention expiry must be after received time");
    }
  }
}
