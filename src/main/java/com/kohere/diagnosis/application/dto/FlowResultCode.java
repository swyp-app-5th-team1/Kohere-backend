package com.kohere.diagnosis.application.dto;

/**
 * v2 서버 주도 진단 흐름({@code POST /api/v2/diagnoses/start}·{@code /next})의 응답 결과코드(issue #157·ADR-0036).
 * 정상 {@code 200} 응답 {@code data}에 실리는 태그드 유니온의 태그이며 에러가 아니다({@code TERMINATED} 포함). 도메인 전이 enum
 * {@code DiagnosisStatus}와 분리한다.
 *
 * <p>서버는 <b>질문과 분기</b>만 주도한다 — 진단 시작과 확정 매물 조회는 클라이언트가 결정한다. ① 지역 0건 예외질문도 별도 코드가 아니라 {@code
 * NEXT_QUESTION}(일반 질문)으로 내려가고, 그 예/아니오 응답에만 클라이언트가 행할 행위를 코드로 알린다({@code RESTART}=재시도 · {@code
 * TERMINATED}=진단종료).
 *
 * <p><b>매칭 0건(no-match)은 이 enum에 없다.</b> 그건 추천을 실제로 조회해야 알 수 있는 사실이라 서버가 확정 시점에 미리 계산해 내려보내지 않는다 —
 * 그러려면 클라이언트가 요청하지도 않은 추천 쿼리를 서버가 돌려야 하고, 이는 "매물 조회 시점은 클라가 정한다"와 배치된다. no-match는 조회를 마친 뒤인 {@code
 * GET /api/v2/diagnoses/{id}/recommendations} 응답의 {@link RecommendationResultCode#NO_MATCH}로 표현된다.
 * 서버가 미리 필터링하는 지점은 ① 지역 하나뿐이다.
 *
 * <ul>
 *   <li>{@code NEXT_QUESTION} — 다음 질문이 남음(마지막 슬롯 전). ① 지역 0건 예외질문({@code field=regionRetry})도 이 코드로
 *       내려간다. {@code question} 채움.
 *   <li>{@code RESTART} — 지역 예외질문 "예" → 클라이언트가 {@code POST /start}로 처음부터 재시도(코드만, 세션 삭제).
 *   <li>{@code COMPLETED} — 빌더 완성 → 자동 확정. {@code diagnosisId} 채움(매칭 유무와 무관 — 매물은 클라가 별도 조회).
 *   <li>{@code TERMINATED} — 지역 예외질문 "아니오" → 진단 종료(코드만, 세션 삭제).
 * </ul>
 */
public enum FlowResultCode {
  NEXT_QUESTION,
  RESTART,
  COMPLETED,
  TERMINATED
}
