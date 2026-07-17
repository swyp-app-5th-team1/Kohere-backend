package com.kohere.diagnosis.domain;

import java.util.Optional;

/**
 * v2 진행 세션 영속 포트(도메인). 구현은 infrastructure의 MongoDB({@code diagnosisFlowSessions}) 어댑터가 제공한다(의존성 역전,
 * docs/convention/code-style.md §3-3). 사용자당 1 세션(userId UNIQUE).
 */
public interface DiagnosisFlowSessionRepository {

  /** 사용자의 진행 세션 조회(없으면 empty). */
  Optional<DiagnosisFlowSession> findByUserId(long userId);

  /** 진행 세션 저장(신규는 id 부여, 기존은 갱신). */
  DiagnosisFlowSession save(DiagnosisFlowSession session);

  /**
   * 사용자의 진행 세션을 주어진 상태로 덮어쓴다(없으면 생성). 흐름을 처음부터 시작할 때 쓴다 — "사용자당 1 세션" UNIQUE와 <b>같은
   * 조건(userId)</b>으로 원자적 upsert하므로, 삭제 후 삽입과 달리 동시 호출(시작 버튼 더블탭)에도 중복 키로 실패하지 않는다.
   *
   * <p>덮어쓰는 이전 세션은 <b>그냥 버린다</b> — 답하다 이탈한 시도는 기록하지 않는다(ADR-0036 결정 12). 이탈은 요청으로 오지 않아 돌아왔을 때에야 알
   * 수 있고, 그러면 영영 안 돌아온 사용자는 빠지고 시각도 실제 이탈 시각이 아니라 재시작 시각이 된다 — 부정확한 데이터라 남기지 않는다.
   */
  DiagnosisFlowSession upsertByUserId(DiagnosisFlowSession session);

  /** 사용자의 진행 세션 삭제(흐름 종료·완료 시). */
  void deleteByUserId(long userId);
}
