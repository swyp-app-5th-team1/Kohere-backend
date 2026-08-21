package com.kohere.report.infrastructure;

import com.kohere.report.domain.Report;
import com.kohere.report.domain.ReportRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 신고 도메인 포트와 Spring Data JPA를 연결하는 영속 어댑터다.
 *
 * <p>응용 계층은 이 클래스의 JPA 엔티티를 보지 않고 순수 {@link Report}만 사용한다.
 */
@Repository
@RequiredArgsConstructor
public class ReportRepositoryImpl implements ReportRepository {

  private final ChatReportJpaRepository jpaRepository;

  /** {@inheritDoc} */
  @Override
  public Report save(Report report) {
    // saveAndFlush를 써야 동시 중복 UNIQUE 충돌이 생성 트랜잭션 안에서 즉시 드러나고,
    // 바깥 조율 서비스가 롤백 뒤 기존 신고를 안전하게 다시 조회할 수 있다.
    return toDomain(jpaRepository.saveAndFlush(toEntity(report)));
  }

  /** {@inheritDoc} */
  @Override
  public Optional<Report> findByReporterIdAndChatRoomId(Long reporterId, Long chatRoomId) {
    return jpaRepository
        .findByReporterIdAndChatRoomId(reporterId, chatRoomId)
        .map(ReportRepositoryImpl::toDomain);
  }

  /** DB 행을 영속 기술이 없는 신고 도메인 객체로 복원한다. */
  private static Report toDomain(ChatReportJpaEntity entity) {
    return Report.builder()
        .id(entity.getId())
        .chatRoomId(entity.getChatRoomId())
        .reporterId(entity.getReporterId())
        .reportedUserId(entity.getReportedUserId())
        .reason(entity.getReason())
        .status(entity.getStatus())
        .evidenceThroughMessageId(entity.getEvidenceThroughMessageId())
        .receivedAt(entity.getReceivedAt())
        .retentionExpiresAt(entity.getRetentionExpiresAt())
        .createdAt(entity.getCreatedAt())
        .updatedAt(entity.getUpdatedAt())
        .build();
  }

  /** 저장할 신고 도메인 객체를 JPA 엔티티로 변환한다. */
  private static ChatReportJpaEntity toEntity(Report report) {
    return ChatReportJpaEntity.builder()
        .id(report.getId())
        .chatRoomId(report.getChatRoomId())
        .reporterId(report.getReporterId())
        .reportedUserId(report.getReportedUserId())
        .reason(report.getReason())
        .status(report.getStatus())
        .evidenceThroughMessageId(report.getEvidenceThroughMessageId())
        .receivedAt(report.getReceivedAt())
        .retentionExpiresAt(report.getRetentionExpiresAt())
        .createdAt(report.getCreatedAt())
        .updatedAt(report.getUpdatedAt())
        .build();
  }
}
