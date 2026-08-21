package com.kohere.report.application;

import com.kohere.report.domain.Report;
import com.kohere.report.domain.ReportReason;
import com.kohere.report.domain.ReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 채팅방 신고 접수의 멱등성과 HTTP 응답 구분을 조율한다.
 *
 * <p>이 서비스 자체는 트랜잭션을 열지 않는다. {@link ReportCreator}의 신규 생성 트랜잭션이 UNIQUE 충돌로 완전히 롤백된 뒤에야 기존 신고를 다시 읽을
 * 수 있어야 하기 때문이다.
 */
@Service
@RequiredArgsConstructor
public class ReportService {

  private final ReportRepository reportRepository;
  private final ReportCreator reportCreator;

  /**
   * 같은 사용자·같은 채팅방 신고를 한 건으로 수렴시킨다.
   *
   * @return 신규 접수면 {@code created=true}, 네트워크 재시도면 기존 신고와 {@code created=false}
   */
  public ReportCreationResult createChatRoomReport(
      long reporterId, long chatRoomId, ReportReason reason) {
    return reportRepository
        .findByReporterIdAndChatRoomId(reporterId, chatRoomId)
        .map(report -> new ReportCreationResult(report, false))
        .orElseGet(() -> createOrFindConcurrentReport(reporterId, chatRoomId, reason));
  }

  /** 동시에 두 요청이 들어와 DB UNIQUE에서 진 요청은 롤백 뒤 승리한 기존 신고를 반환한다. */
  private ReportCreationResult createOrFindConcurrentReport(
      long reporterId, long chatRoomId, ReportReason reason) {
    try {
      Report created = reportCreator.create(reporterId, chatRoomId, reason);
      return new ReportCreationResult(created, true);
    } catch (DataIntegrityViolationException conflict) {
      return reportRepository
          .findByReporterIdAndChatRoomId(reporterId, chatRoomId)
          .map(report -> new ReportCreationResult(report, false))
          // 기존 신고가 없다면 신고 UNIQUE가 아닌 다른 DB 결함이므로 원래 예외를 숨기지 않는다.
          .orElseThrow(() -> conflict);
    }
  }
}
