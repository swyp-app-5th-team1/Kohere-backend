package com.kohere.gamification.presentation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 정답 제출 요청. {@code selectedChoice}는 보기 키 {@code A}~{@code D} 중 하나여야 한다(그 외 값·빈 값·누락 시 {@code
 * INVALID_INPUT}). docs/api/specs/06-gamification.md.
 */
public record AnswerQuizRequest(@NotBlank @Pattern(regexp = "[A-D]") String selectedChoice) {}
