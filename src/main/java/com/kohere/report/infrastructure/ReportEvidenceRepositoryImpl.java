package com.kohere.report.infrastructure;

import com.kohere.report.domain.ReportEvidence;
import com.kohere.report.domain.ReportEvidenceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/** 신고 증거 도메인 포트와 {@code chat_report_evidence} JPA 저장소를 연결한다. */
@Repository
@RequiredArgsConstructor
public class ReportEvidenceRepositoryImpl implements ReportEvidenceRepository {

  private final ChatReportEvidenceJpaRepository jpaRepository;

  /** {@inheritDoc} */
  @Override
  public ReportEvidence save(ReportEvidence evidence) {
    return toDomain(jpaRepository.save(toEntity(evidence)));
  }

  /** JPA 엔티티를 불변 증거 도메인 객체로 복원한다. */
  private static ReportEvidence toDomain(ChatReportEvidenceJpaEntity entity) {
    return ReportEvidence.builder()
        .id(entity.getId())
        .reportId(entity.getReportId())
        .schemaVersion(entity.getSchemaVersion())
        .evidenceThroughMessageId(entity.getEvidenceThroughMessageId())
        .snapshot(entity.getSnapshot())
        .contentHash(entity.getContentHash())
        .capturedAt(entity.getCapturedAt())
        .createdAt(entity.getCreatedAt())
        .build();
  }

  /** 증거 도메인 객체를 MySQL JSON 컬럼용 JPA 엔티티로 변환한다. */
  private static ChatReportEvidenceJpaEntity toEntity(ReportEvidence evidence) {
    return ChatReportEvidenceJpaEntity.builder()
        .id(evidence.getId())
        .reportId(evidence.getReportId())
        .schemaVersion(evidence.getSchemaVersion())
        .evidenceThroughMessageId(evidence.getEvidenceThroughMessageId())
        .snapshot(evidence.getSnapshot())
        .contentHash(evidence.getContentHash())
        .capturedAt(evidence.getCapturedAt())
        .createdAt(evidence.getCreatedAt())
        .build();
  }
}
