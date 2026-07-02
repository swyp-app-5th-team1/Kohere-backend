package com.kohere.gamification.application.dto;

import java.util.List;

/**
 * 랜덤 퀴즈 조회 응답. 문항·보기 텍스트는 사용자 언어로 번역된 표시 문자열이며, 정답·해설은 포함하지 않는다(ADR-0035).
 *
 * @param quizId 퀴즈 식별자(채점 시 경로로 사용)
 * @param question 사용자 언어로 번역된 문항
 * @param choices 4지선다 보기
 */
public record RandomQuizResponse(Long quizId, String question, List<Choice> choices) {

  /**
   * @param key 언어 무관 보기 키(A~D)
   * @param text 사용자 언어로 번역된 보기 텍스트
   */
  public record Choice(String key, String text) {}
}
