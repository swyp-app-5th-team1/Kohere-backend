package com.kohere.report.infrastructure;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data가 {@code chat_reports}의 INSERT와 중복 신고 조회 SQL을 생성한다. */
interface ChatReportJpaRepository extends JpaRepository<ChatReportJpaEntity, Long> {

  /** DB UNIQUE와 같은 키로 기존 신고를 조회한다. */
  Optional<ChatReportJpaEntity> findByReporterIdAndChatRoomId(Long reporterId, Long chatRoomId);
}
