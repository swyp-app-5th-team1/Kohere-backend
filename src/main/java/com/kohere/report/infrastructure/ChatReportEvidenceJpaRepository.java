package com.kohere.report.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data가 {@code chat_report_evidence}의 INSERT를 수행한다. */
interface ChatReportEvidenceJpaRepository
    extends JpaRepository<ChatReportEvidenceJpaEntity, Long> {}
