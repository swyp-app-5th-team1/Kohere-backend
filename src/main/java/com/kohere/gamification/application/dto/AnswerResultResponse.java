package com.kohere.gamification.application.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 정답 제출·채점 결과(무상태). 정답·오답 모두 해설({@code explanation}, 사용자 언어 번역)을 반환하며, 오답이면 정답 보기({@code
 * correctChoice})도 함께 반환한다. {@code null} 필드는 응답에서 생략한다(ADR-0035).
 *
 * @param quizId 채점 대상 퀴즈 ID
 * @param selectedChoice 사용자가 제출한 보기 키(A~D)
 * @param correct 정답 여부(서버 판정)
 * @param correctChoice 정답 보기 키(오답 시에만)
 * @param explanation 해설(정답·오답 모두, 사용자 언어 번역)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnswerResultResponse(
    Long quizId,
    String selectedChoice,
    boolean correct,
    String correctChoice,
    String explanation) {}
