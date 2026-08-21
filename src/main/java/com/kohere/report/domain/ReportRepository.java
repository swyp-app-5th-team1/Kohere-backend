package com.kohere.report.domain;

import java.util.Optional;

/**
 * 채팅방 신고 기본 정보를 저장하고 찾는 도메인 포트다.
 *
 * <p>인터페이스는 JPA를 모르며 infrastructure 어댑터가 실제 {@code chat_reports} SQL을 수행한다.
 */
public interface ReportRepository {

  /** 신규 신고를 저장하고 DB가 발급한 reportId를 포함해 반환한다. */
  Report save(Report report);

  /** 같은 사용자가 같은 채팅방을 이미 신고했는지 확인해 네트워크 재시도를 멱등 처리한다. */
  Optional<Report> findByReporterIdAndChatRoomId(Long reporterId, Long chatRoomId);
}
