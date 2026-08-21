package com.kohere.report.domain;

import java.time.Instant;
import lombok.Builder;
import lombok.Getter;

/** 신고 한 건에 연결된 최초 원문 증거 스냅샷과 무결성 해시다. */
@Getter
public class ReportEvidence {

  /** 현재 JSON 구조의 버전이다. 후속 필드 변경 시 기존 증거를 해석할 기준으로 사용한다. */
  public static final int CURRENT_SCHEMA_VERSION = 1;

  private final Long id;
  private final Long reportId;
  private final int schemaVersion;
  private final Long evidenceThroughMessageId;
  private final ReportEvidenceSnapshot snapshot;
  private final String contentHash;
  private final Instant capturedAt;
  private final Instant createdAt;

  /** 증거 저장에 필요한 필드와 SHA-256 형식을 객체 생성 시점에 확인한다. */
  @Builder
  private ReportEvidence(
      Long id,
      Long reportId,
      int schemaVersion,
      Long evidenceThroughMessageId,
      ReportEvidenceSnapshot snapshot,
      String contentHash,
      Instant capturedAt,
      Instant createdAt) {
    this.id = id;
    this.reportId = reportId;
    this.schemaVersion = schemaVersion;
    this.evidenceThroughMessageId = evidenceThroughMessageId;
    this.snapshot = snapshot;
    this.contentHash = contentHash;
    this.capturedAt = capturedAt;
    this.createdAt = createdAt;
    validate();
  }

  /** 불완전한 증거 객체가 JPA까지 내려가 DB 오류로 늦게 발견되지 않게 한다. */
  private void validate() {
    if (reportId == null
        || evidenceThroughMessageId == null
        || snapshot == null
        || contentHash == null
        || capturedAt == null
        || createdAt == null) {
      throw new IllegalArgumentException("report evidence fields are required");
    }
    if (schemaVersion != CURRENT_SCHEMA_VERSION) {
      throw new IllegalArgumentException("unsupported report evidence schema version");
    }
    if (evidenceThroughMessageId <= 0) {
      throw new IllegalArgumentException("evidence message ID must be positive");
    }
    if (!contentHash.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("content hash must be a lowercase SHA-256 value");
    }
  }
}
