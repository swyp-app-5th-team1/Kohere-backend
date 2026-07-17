package com.kohere.diagnosis.domain;

import lombok.Builder;
import lombok.Getter;

/**
 * v2 서버 주도 진단 흐름의 진행 세션 애그리거트 루트(issue #157·ADR-0036). 사용자당 최대 1건이며, v1의 진행 중 초안({@link Diagnosis}
 * {@code IN_PROGRESS})을 공유하지 않고 별도로 둔다 — {@code pendingField} 같은 절차 필드가 {@code Diagnosis}에 없고 "사용자당
 * IN_PROGRESS 1건" 제약과 충돌하기 때문이다.
 *
 * <p>{@code draft}에 단계별 답을 채워 가다가 마지막 슬롯({@code ARC_STATUS})을 답하면 응용 계층이 {@code
 * draft.complete(now)}로 정본 {@link Diagnosis}를 확정해 {@code diagnoses}에 저장하고 세션은 삭제한다. 영속(MongoDB) 매핑은
 * infrastructure 어댑터가 처리한다.
 *
 * <p><b>진행의 단일 정본은 {@code pendingField}(서버가 직전에 낸 문항의 field)다</b> — 답한 필드의 null 여부로 진행을 추론하지 않는다
 * ({@link Diagnosis#validateComplete()}가 {@code conditions}(④)를 필수로 보지 않고 ⑥ {@code arcStatus}가
 * {@code conditions}를 교차 기록해 추론이 불안정하다). 진행 슬롯 수를 따로 세지 않는 이유는, 흐름에 정본 6슬롯에 <b>없는</b> 문항(① 지역 0건
 * 예외질문 {@code regionRetry})이 끼어들 수 있어 "몇 개 답했나"와 "무엇을 물었나"가 어긋나기 때문이다 — 후자만 있으면 둘 다 답할 수 있다.
 *
 * <p>세션은 클라이언트가 {@code POST /start}로 시작할 때만 생기고 터미널(확정·재시도·종료)에서 삭제된다 — 진행 중 세션이 있어도 {@code
 * /start}는 그것을 버리고 새로 만든다(진단을 중단했다 다시 시작하면 언제나 처음부터). 그래서 진행 중 세션을 되돌리는 전이는 두지 않는다.
 */
@Getter
@Builder(toBuilder = true)
public class DiagnosisFlowSession {

  private final String id;
  private final Long userId;
  private final Diagnosis draft;

  /**
   * 서버가 직전에 낸 문항의 {@code field} — 다음 {@code /next}가 받을 수 있는 유일한 답이자 <b>진행 위치의 단일 정본</b>이다. 다음 슬롯은
   * {@code DiagnosisFlowStep.ofField(답한 field).next()}로 정한다.
   */
  private final String pendingField;

  /** 새 진행 세션을 시작한다(빈 초안). 첫 문항은 언제나 ① 지역이다. id는 영속 시 부여한다. */
  public static DiagnosisFlowSession start(long userId) {
    return DiagnosisFlowSession.builder()
        .userId(userId)
        .draft(Diagnosis.startInProgress(userId))
        .pendingField(DiagnosisFlowStep.REGION.field())
        .build();
  }

  /** 현재 슬롯 답을 적용한 새 초안으로 교체한 세션. */
  public DiagnosisFlowSession withDraft(Diagnosis newDraft) {
    return toBuilder().draft(newDraft).build();
  }

  /** 방금 낸 문항의 {@code field}를 기대 답으로 기록한 세션(정본 슬롯 문항·예외질문 공통). */
  public DiagnosisFlowSession awaiting(String field) {
    return toBuilder().pendingField(field).build();
  }
}
