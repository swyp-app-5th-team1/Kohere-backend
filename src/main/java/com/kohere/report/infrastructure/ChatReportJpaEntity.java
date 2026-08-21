package com.kohere.report.infrastructure;

import com.kohere.report.domain.ReportReason;
import com.kohere.report.domain.ReportStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** {@code chat_reports}와 매핑되는 신고 기본 정보 JPA 엔티티다. */
@Entity
@Table(name = "chat_reports")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
class ChatReportJpaEntity {

  /** 관리자가 신고 한 건을 식별하고 API가 {@code reportId}로 반환할 서버 번호다. */
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** 신고가 발생한 {@code chat_rooms.id}를 no-FK 숫자 참조로 저장한다. */
  @Column(nullable = false)
  private Long chatRoomId;

  /** JWT에서 확인한 신고자 {@code users.id}다. */
  @Column(nullable = false)
  private Long reporterId;

  /** 채팅방 참여자 중 신고자가 아닌 상대방 {@code users.id}다. */
  @Column(nullable = false)
  private Long reportedUserId;

  /** 프런트 문구와 분리된 언어 무관 enum 코드를 저장한다. */
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 64)
  private ReportReason reason;

  /** 현재 단계는 접수만 담당하므로 RECEIVED 하나이며 관리자 기능에서 상태를 확장한다. */
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private ReportStatus status;

  /** 증거에 포함된 가장 큰 messageId로 신고 당시의 시간축 상한을 고정한다. */
  @Column(nullable = false)
  private Long evidenceThroughMessageId;

  /** 사용자가 신고 버튼을 눌러 서버가 접수를 확정한 UTC 시각이다. */
  @Column(nullable = false)
  private Instant receivedAt;

  /** 화면 고지대로 접수 시점부터 1년 뒤인 보관 만료 시각이다. 자동 파기는 후속 작업이다. */
  @Column(nullable = false)
  private Instant retentionExpiresAt;

  /** 최초 INSERT 시각이다. */
  @Column(nullable = false)
  private Instant createdAt;

  /** 후속 관리자 상태 변경까지 고려한 마지막 변경 시각이다. */
  @Column(nullable = false)
  private Instant updatedAt;
}
