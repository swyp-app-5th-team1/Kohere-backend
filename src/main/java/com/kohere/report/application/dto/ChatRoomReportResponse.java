package com.kohere.report.application.dto;

import com.kohere.report.domain.Report;
import com.kohere.report.domain.ReportReason;
import com.kohere.report.domain.ReportStatus;
import java.time.Instant;

/** 프런트가 신고 접수 성공과 중복 재시도 결과를 확인할 공개 응답이다. */
public record ChatRoomReportResponse(
    Long reportId, Long chatRoomId, ReportReason reason, ReportStatus status, Instant receivedAt) {

  /** 신고 대상 사용자와 민감한 증거는 노출하지 않고 접수 확인에 필요한 값만 복사한다. */
  public static ChatRoomReportResponse from(Report report) {
    return new ChatRoomReportResponse(
        report.getId(),
        report.getChatRoomId(),
        report.getReason(),
        report.getStatus(),
        report.getReceivedAt());
  }
}
