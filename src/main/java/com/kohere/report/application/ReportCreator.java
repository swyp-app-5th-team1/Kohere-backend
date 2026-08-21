package com.kohere.report.application;

import com.kohere.chat.api.ChatReportEvidenceProvider;
import com.kohere.chat.api.ChatReportEvidenceSnapshot;
import com.kohere.report.domain.Report;
import com.kohere.report.domain.ReportEvidence;
import com.kohere.report.domain.ReportEvidenceMessage;
import com.kohere.report.domain.ReportEvidenceRepository;
import com.kohere.report.domain.ReportEvidenceSnapshot;
import com.kohere.report.domain.ReportReason;
import com.kohere.report.domain.ReportRepository;
import com.kohere.report.domain.ReportRequiresTextMessageException;
import com.kohere.report.domain.ReportStatus;
import java.time.Instant;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 채팅 원문 증거를 캡처하고 신고 기본 행과 증거 행을 하나의 트랜잭션으로 저장한다.
 *
 * <p>{@link ReportService}와 별도 Spring 컴포넌트인 이유는 UNIQUE 충돌로 이 트랜잭션이 롤백된 뒤, 바깥 서비스가 깨끗한 트랜잭션에서 기존 신고를
 * 다시 조회해야 하기 때문이다.
 */
@Component
@RequiredArgsConstructor
public class ReportCreator {

  private final ChatReportEvidenceProvider evidenceProvider;
  private final ReportRepository reportRepository;
  private final ReportEvidenceRepository evidenceRepository;
  private final ReportEvidenceHasher evidenceHasher;

  /**
   * 신규 신고와 최초 증거를 원자적으로 저장한다.
   *
   * @param reporterId JWT에서 확인한 신고자 ID
   * @param chatRoomId 신고할 현재 채팅방 ID
   * @param reason 프런트가 선택한 언어 무관 신고 사유 코드
   * @return DB가 발급한 ID를 포함한 신규 신고
   */
  @Transactional
  public Report create(long reporterId, long chatRoomId, ReportReason reason) {
    // chat 모듈이 room/member 잠금과 개인 숨김 경계를 적용한 뒤 공개 스냅샷만 돌려준다.
    ChatReportEvidenceSnapshot source = evidenceProvider.capture(reporterId, chatRoomId);
    if (source.messages().isEmpty() || source.evidenceThroughMessageId() == null) {
      throw new ReportRequiresTextMessageException();
    }

    Instant receivedAt = source.capturedAt();
    // 단순 365일이 아니라 UTC 달력 기준 1년을 더해 윤년에도 화면 고지와 같은 만료일을 만든다.
    Instant retentionExpiresAt = receivedAt.atZone(ZoneOffset.UTC).plusYears(1).toInstant();

    Report savedReport =
        reportRepository.save(
            Report.builder()
                .chatRoomId(source.chatRoomId())
                .reporterId(source.reporterId())
                .reportedUserId(source.reportedUserId())
                .reason(reason)
                .status(ReportStatus.RECEIVED)
                .evidenceThroughMessageId(source.evidenceThroughMessageId())
                .receivedAt(receivedAt)
                .retentionExpiresAt(retentionExpiresAt)
                .createdAt(receivedAt)
                .updatedAt(receivedAt)
                .build());

    ReportEvidenceSnapshot evidenceSnapshot = toReportSnapshot(source);
    evidenceRepository.save(
        ReportEvidence.builder()
            .reportId(savedReport.getId())
            .schemaVersion(ReportEvidence.CURRENT_SCHEMA_VERSION)
            .evidenceThroughMessageId(source.evidenceThroughMessageId())
            .snapshot(evidenceSnapshot)
            .contentHash(evidenceHasher.hash(evidenceSnapshot))
            .capturedAt(source.capturedAt())
            .createdAt(receivedAt)
            .build());

    return savedReport;
  }

  /** chat 공개 record를 report 모듈이 정해진 보관기간 동안 소유할 증거 record로 복사한다. */
  private static ReportEvidenceSnapshot toReportSnapshot(ChatReportEvidenceSnapshot source) {
    return new ReportEvidenceSnapshot(
        source.chatRoomId(),
        source.listingId(),
        source.reporterId(),
        source.reportedUserId(),
        source.messages().stream()
            .map(
                message ->
                    new ReportEvidenceMessage(
                        message.messageId(),
                        message.senderId(),
                        message.originalContent(),
                        message.sentAt()))
            .toList());
  }
}
