package com.kohere.report.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.kohere.chat.api.ChatReportEvidenceProvider;
import com.kohere.chat.api.ChatReportEvidenceSnapshot;
import com.kohere.chat.api.ChatReportMessageSnapshot;
import com.kohere.report.domain.Report;
import com.kohere.report.domain.ReportEvidence;
import com.kohere.report.domain.ReportEvidenceRepository;
import com.kohere.report.domain.ReportReason;
import com.kohere.report.domain.ReportRepository;
import com.kohere.report.domain.ReportRequiresTextMessageException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 신고와 증거를 만들 때 서버가 정한 참여자·원문·1년 보관 규칙을 사용하는지 검증한다. */
@ExtendWith(MockitoExtension.class)
class ReportCreatorTest {

  private static final long ROOM_ID = 10L;
  private static final long REPORTER_ID = 7L;
  private static final long REPORTED_ID = 42L;
  private static final Instant CAPTURED_AT = Instant.parse("2024-02-29T10:00:00Z");

  @Mock private ChatReportEvidenceProvider evidenceProvider;
  @Mock private ReportRepository reportRepository;
  @Mock private ReportEvidenceRepository evidenceRepository;
  @Mock private ReportEvidenceHasher evidenceHasher;

  private ReportCreator creator;

  @BeforeEach
  void setUp() {
    creator =
        new ReportCreator(evidenceProvider, reportRepository, evidenceRepository, evidenceHasher);
  }

  /** 윤년 접수도 달력 기준 1년을 적용하고 신고·증거가 같은 원문 상한을 쓰는지 확인한다. */
  @Test
  @DisplayName("채팅 원문 스냅샷으로 신고와 증거를 함께 만든다")
  void createsReportAndEvidenceFromChatSnapshot() {
    given(evidenceProvider.capture(REPORTER_ID, ROOM_ID)).willReturn(snapshotWithText());
    given(evidenceHasher.hash(org.mockito.ArgumentMatchers.any())).willReturn("a".repeat(64));
    given(reportRepository.save(org.mockito.ArgumentMatchers.any()))
        .willAnswer(
            invocation -> {
              Report value = invocation.getArgument(0);
              return Report.builder()
                  .id(15L)
                  .chatRoomId(value.getChatRoomId())
                  .reporterId(value.getReporterId())
                  .reportedUserId(value.getReportedUserId())
                  .reason(value.getReason())
                  .status(value.getStatus())
                  .evidenceThroughMessageId(value.getEvidenceThroughMessageId())
                  .receivedAt(value.getReceivedAt())
                  .retentionExpiresAt(value.getRetentionExpiresAt())
                  .createdAt(value.getCreatedAt())
                  .updatedAt(value.getUpdatedAt())
                  .build();
            });

    Report result = creator.create(REPORTER_ID, ROOM_ID, ReportReason.ILLEGAL_CONTENT);

    assertThat(result.getId()).isEqualTo(15L);
    assertThat(result.getReportedUserId()).isEqualTo(REPORTED_ID);
    assertThat(result.getRetentionExpiresAt()).isEqualTo(Instant.parse("2025-02-28T10:00:00Z"));

    ArgumentCaptor<ReportEvidence> evidenceCaptor = ArgumentCaptor.forClass(ReportEvidence.class);
    verify(evidenceRepository).save(evidenceCaptor.capture());
    ReportEvidence evidence = evidenceCaptor.getValue();
    assertThat(evidence.getReportId()).isEqualTo(15L);
    assertThat(evidence.getEvidenceThroughMessageId()).isEqualTo(101L);
    assertThat(evidence.getSnapshot().messages())
        .singleElement()
        .satisfies(message -> assertThat(message.originalContent()).isEqualTo("original text"));
  }

  /** 카드만 있거나 빈 방이라 TEXT가 없으면 신고 행도 증거 행도 저장하지 않는다. */
  @Test
  @DisplayName("현재 보이는 TEXT가 없으면 신고하지 않는다")
  void rejectsRoomWithoutVisibleText() {
    given(evidenceProvider.capture(REPORTER_ID, ROOM_ID))
        .willReturn(
            new ChatReportEvidenceSnapshot(
                ROOM_ID, "listing-1", REPORTER_ID, REPORTED_ID, null, List.of(), CAPTURED_AT));

    assertThatThrownBy(() -> creator.create(REPORTER_ID, ROOM_ID, ReportReason.ILLEGAL_CONTENT))
        .isInstanceOf(ReportRequiresTextMessageException.class);

    verify(reportRepository, never()).save(org.mockito.ArgumentMatchers.any());
    verify(evidenceRepository, never()).save(org.mockito.ArgumentMatchers.any());
  }

  private static ChatReportEvidenceSnapshot snapshotWithText() {
    return new ChatReportEvidenceSnapshot(
        ROOM_ID,
        "listing-1",
        REPORTER_ID,
        REPORTED_ID,
        101L,
        List.of(
            new ChatReportMessageSnapshot(
                101L, REPORTED_ID, "original text", CAPTURED_AT.minusSeconds(10))),
        CAPTURED_AT);
  }
}
