package com.kohere.diagnosis.domain;

/**
 * 진단 상태. 진행 중 작성은 {@code IN_PROGRESS}, 제출(확정) 완료 시 {@code COMPLETED}로 전이한다(사용자당 진행 중 1건). 이력·최근 조회는
 * {@code COMPLETED}만 노출한다. docs/api/specs/02-diagnosis-recommendation.md (status).
 *
 * <ul>
 *   <li>{@code IN_PROGRESS} — v1의 진행 중 초안(살아 있음, 사용자당 1건). 이력·최근 조회에 안 나온다.
 *   <li>{@code COMPLETED} — 6단계를 다 답하고 확정됨({@link Diagnosis#complete}). 이력·최근·추천의 대상.
 *   <li>{@code DISCARDED} — 6단계를 못 채우고 <b>끝난</b> v2 시도({@link Diagnosis#discard}). 수요 분석 전용 기록이라
 *       사용자 노출 경로를 두지 않는다: 목록(이력·최근)은 {@code COMPLETED}만 보므로 자동으로 빠지지만, <b>id로 직접 오는 상세·추천은 소유권만
 *       보므로 명시적으로 404로 거절</b>한다 — 폐기 기록은 본인 것이고 진단 id가 순차 발급이라 추측 가능하기 때문이다(ADR-0036 결정 12).
 * </ul>
 */
public enum DiagnosisStatus {
  IN_PROGRESS,
  COMPLETED,
  DISCARDED
}
