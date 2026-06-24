package com.kohere.diagnosis.domain;

import java.util.List;
import java.util.Map;

/**
 * 추천 0건 조정 제안 카탈로그 항목(도메인 값). 표시 문자열(message·action detail)은 언어 코드를 키로 하는 인라인 맵으로 보유하며, 응용 계층이 사용자
 * 언어로 골라 표시 DTO를 조립한다(ADR-0029). {@code reason}/{@code type}은 언어 무관 식별 키다.
 *
 * @param reason 제안 사유 식별 키(언어 무관, 예: {@code NO_MATCH})
 * @param message 사유 표시 문자열의 언어-키 맵(예: {@code {"en": "...", "ko": "..."}})
 * @param actions 조정 액션 목록(type + 언어-키 맵 detail)
 */
public record SuggestionContent(String reason, Map<String, String> message, List<Action> actions) {

  /** 조정 액션. {@code type}=언어 무관 식별 키(예: {@code RELAX_REGION}), {@code detail}=표시 문자열 언어-키 맵. */
  public record Action(String type, Map<String, String> detail) {}
}
