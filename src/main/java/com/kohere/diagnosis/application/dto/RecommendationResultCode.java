package com.kohere.diagnosis.application.dto;

/**
 * v2 진단 결과 추천({@code GET /api/v2/diagnoses/{id}/recommendations}) 응답의 매칭 결과코드(issue #157·ADR-0036).
 * 정상 {@code 200} 응답 {@code data}에 항상 실리며 에러가 아니다 — {@code NO_MATCH}도 "조건에 맞는 매물이 없다"는 정상 결과다.
 *
 * <p>v1(§7)은 같은 사실을 {@code suggestions.reason="NO_MATCH"}로 알리되 조정 제안 문구·액션과 <b>한 덩어리로 묶어</b> 준다.
 * v2는 제안을 쓰지 않기로 했으므로(요구3) 그 덩어리에서 <b>사유만</b> 떼어내 최상위 결과코드로 둔다 — 제안을 안 쓰는 것과 사유를 안 주는 것은 다르다.
 *
 * <p>흐름 응답({@link FlowResultCode})에는 {@code NO_MATCH}가 없다. 그쪽은 아직 추천을 조회하기 <b>전</b>이라 모르는 사실이고, 여기는
 * 조회를 마친 뒤라 아는 사실이다.
 *
 * <ul>
 *   <li>{@code MATCHED} — 조건에 맞는 매물이 있음({@code content} 비어 있지 않음).
 *   <li>{@code NO_MATCH} — 조건에 맞는 매물이 0건({@code content}·{@code markers} 빈 목록, 조정 제안 없음).
 * </ul>
 */
public enum RecommendationResultCode {
  MATCHED,
  NO_MATCH
}
