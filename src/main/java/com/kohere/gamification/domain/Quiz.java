package com.kohere.gamification.domain;

import java.util.List;
import java.util.Map;

/**
 * 학습 퀴즈 카탈로그 항목(도메인 값). 표시 문자열({@code question}·{@code choices[].text}·{@code explanation})은 언어 코드를
 * 키로 하는 인라인 맵으로 보유하며, 응용 계층이 사용자 언어로 골라 표시 DTO를 조립한다(ADR-0029). {@code correctChoice}는 언어 무관 채점 키다.
 * 무상태 랜덤 학습 퀴즈이며 제출 기록·포인트가 없다(ADR-0035).
 *
 * @param id 퀴즈 식별자
 * @param question 언어-키 맵 문항(예: {@code {"en": "...", "ko": "..."}})
 * @param choices 4지선다 보기(키 A~D + 언어-키 맵 텍스트)
 * @param correctChoice 정답 보기 키(A~D). 조회 응답에는 노출하지 않고 오답 채점 시에만 반환한다
 * @param explanation 오답 사유·해설 언어-키 맵. 오답 채점 시에만 반환한다
 */
public record Quiz(
    Long id,
    Map<String, String> question,
    List<QuizChoice> choices,
    ChoiceKey correctChoice,
    Map<String, String> explanation) {

  /** 선택 보기가 정답인지 저장된 정답 키와 대조해 판정한다(무상태 서버 채점). */
  public boolean isCorrect(ChoiceKey selected) {
    return correctChoice == selected;
  }
}
