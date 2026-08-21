package com.kohere.report.infrastructure;

import com.kohere.report.domain.ReportEvidenceSnapshot;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** {@code chat_report_evidence}의 민감한 원문 JSON과 무결성 해시를 매핑하는 JPA 엔티티다. */
@Entity
@Table(name = "chat_report_evidence")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
class ChatReportEvidenceJpaEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** 한 신고에 최초 증거 한 건만 저장하며 DB UNIQUE가 이를 최종 보장한다. */
  @Column(nullable = false)
  private Long reportId;

  /** JSON 구조 변경 뒤에도 기존 행을 올바르게 해석할 수 있게 하는 스키마 버전이다. */
  @Column(nullable = false)
  private int schemaVersion;

  /** 기본 신고 행과 같은 증거 상한을 중복 저장해 두 행의 정합성을 점검할 수 있게 한다. */
  @Column(nullable = false)
  private Long evidenceThroughMessageId;

  /** Hibernate가 불변 record를 MySQL JSON 객체로 직렬화·역직렬화한다. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "json")
  private ReportEvidenceSnapshot snapshot;

  /** snapshot record를 고정 순서로 직렬화한 바이트의 소문자 SHA-256 64자리 값이다. */
  @Column(nullable = false, length = 64)
  private String contentHash;

  /** 채팅 모듈에서 원문을 복사해 온 UTC 시각이다. */
  @Column(nullable = false)
  private Instant capturedAt;

  @Column(nullable = false)
  private Instant createdAt;
}
