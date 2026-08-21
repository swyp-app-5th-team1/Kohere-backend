package com.kohere.report.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.kohere.report.domain.Report;
import com.kohere.report.domain.ReportReason;
import com.kohere.report.domain.ReportRepository;
import com.kohere.report.domain.ReportStatus;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/** 신고 접수의 신규·재시도·동시 UNIQUE 충돌 결과가 201/200 판단값으로 수렴하는지 검증한다. */
@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

  private static final long REPORTER_ID = 7L;
  private static final long ROOM_ID = 10L;

  @Mock private ReportRepository reportRepository;
  @Mock private ReportCreator reportCreator;

  private ReportService service;

  @BeforeEach
  void setUp() {
    service = new ReportService(reportRepository, reportCreator);
  }

  /** 처음 들어온 요청은 creator 결과와 created=true를 반환한다. */
  @Test
  @DisplayName("새 신고는 신규 생성 결과를 반환한다")
  void newReportReturnsCreated() {
    Report created = report(15L, ReportReason.SPAM);
    given(reportRepository.findByReporterIdAndChatRoomId(REPORTER_ID, ROOM_ID))
        .willReturn(Optional.empty());
    given(reportCreator.create(REPORTER_ID, ROOM_ID, ReportReason.SPAM)).willReturn(created);

    ReportCreationResult result =
        service.createChatRoomReport(REPORTER_ID, ROOM_ID, ReportReason.SPAM);

    assertThat(result.created()).isTrue();
    assertThat(result.report()).isSameAs(created);
  }

  /** 같은 방 신고를 다시 보내면 새 사유로 덮지 않고 최초 접수 결과를 그대로 돌려준다. */
  @Test
  @DisplayName("기존 신고 재요청은 기존 결과를 반환한다")
  void duplicateRequestReturnsExistingReport() {
    Report existing = report(15L, ReportReason.SPAM);
    given(reportRepository.findByReporterIdAndChatRoomId(REPORTER_ID, ROOM_ID))
        .willReturn(Optional.of(existing));

    ReportCreationResult result =
        service.createChatRoomReport(REPORTER_ID, ROOM_ID, ReportReason.OTHER);

    assertThat(result.created()).isFalse();
    assertThat(result.report().getReason()).isEqualTo(ReportReason.SPAM);
    verify(reportCreator, never())
        .create(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.any(ReportReason.class));
  }

  /** 거의 동시에 생성한 다른 요청이 DB UNIQUE에서 먼저 이기면 롤백 뒤 그 신고를 읽어 멱등 성공으로 처리한다. */
  @Test
  @DisplayName("동시 UNIQUE 충돌은 승리한 기존 신고를 반환한다")
  void concurrentConflictReturnsWinningReport() {
    Report winner = report(20L, ReportReason.ILLEGAL_CONTENT);
    given(reportRepository.findByReporterIdAndChatRoomId(REPORTER_ID, ROOM_ID))
        .willReturn(Optional.empty())
        .willReturn(Optional.of(winner));
    given(reportCreator.create(REPORTER_ID, ROOM_ID, ReportReason.ILLEGAL_CONTENT))
        .willThrow(new DataIntegrityViolationException("duplicate"));

    ReportCreationResult result =
        service.createChatRoomReport(REPORTER_ID, ROOM_ID, ReportReason.ILLEGAL_CONTENT);

    assertThat(result.created()).isFalse();
    assertThat(result.report()).isSameAs(winner);
  }

  private static Report report(long reportId, ReportReason reason) {
    Instant now = Instant.parse("2026-08-22T10:00:00Z");
    return Report.builder()
        .id(reportId)
        .chatRoomId(ROOM_ID)
        .reporterId(REPORTER_ID)
        .reportedUserId(42L)
        .reason(reason)
        .status(ReportStatus.RECEIVED)
        .evidenceThroughMessageId(101L)
        .receivedAt(now)
        .retentionExpiresAt(Instant.parse("2027-08-22T10:00:00Z"))
        .createdAt(now)
        .updatedAt(now)
        .build();
  }
}
