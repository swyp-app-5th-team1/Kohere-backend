package com.kohere.diagnosis.application.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * v2 서버 주도 진단 흐름({@code POST /api/v2/diagnoses/start}·{@code /next}) 응답 DTO. {@code resultCode}(태그드
 * 유니온의 태그)에 따라 채워지는 payload가 다르며, 채워지지 않는 필드는 직렬화에서 생략한다({@code NON_NULL}). 정본: issue
 * #157·ADR-0036.
 *
 * <p>확정 시에도 추천 매물을 인라인으로 싣지 않고 <b>매칭 유무조차 확인하지 않는다</b> — 매물 조회 시점은 클라이언트가 정해 {@code diagnosisId}로
 * {@code GET /api/v2/diagnoses/{id}/recommendations}를 호출하며, 0건인지는 그 응답의 {@code resultCode}({@link
 * RecommendationResultCode#NO_MATCH})가 알려준다.
 *
 * @param resultCode 결과코드(항상 존재)
 * @param question NEXT_QUESTION일 때의 질문 1개(그 외 null)
 * @param diagnosisId COMPLETED일 때 확정된 진단 식별자(그 외 null)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DiagnosisFlowResponse(
    FlowResultCode resultCode, QuestionResponse question, Long diagnosisId) {

  /** 다음 질문(① 지역 0건 예외질문 {@code regionRetry} 포함 — 일반 질문과 같은 코드로 내려간다). */
  public static DiagnosisFlowResponse nextQuestion(QuestionResponse question) {
    return new DiagnosisFlowResponse(FlowResultCode.NEXT_QUESTION, question, null);
  }

  /** 지역 예외질문 "예" → 클라이언트가 {@code POST /start}로 처음부터 재시도한다. */
  public static DiagnosisFlowResponse restart() {
    return new DiagnosisFlowResponse(FlowResultCode.RESTART, null, null);
  }

  /**
   * 자동 확정 → 클라이언트가 {@code diagnosisId}로 추천을 별도 조회한다. 매칭 유무는 확인하지 않는다 — 0건인지는 그 조회 응답의 {@code
   * resultCode=NO_MATCH}가 알려준다.
   */
  public static DiagnosisFlowResponse completed(Long diagnosisId) {
    return new DiagnosisFlowResponse(FlowResultCode.COMPLETED, null, diagnosisId);
  }

  /** 지역 예외질문 "아니오" → 진단 종료. */
  public static DiagnosisFlowResponse terminated() {
    return new DiagnosisFlowResponse(FlowResultCode.TERMINATED, null, null);
  }
}
