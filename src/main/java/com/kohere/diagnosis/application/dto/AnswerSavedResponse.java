package com.kohere.diagnosis.application.dto;

/**
 * 단계별 답 저장 응답 DTO. 진행 중(IN_PROGRESS) 진단에 현재 step의 답이 저장되었음을 알린다.
 *
 * <p>docs/api/specs/02-diagnosis-recommendation.md §2.
 *
 * @param saved 저장 성공 여부(항상 true)
 */
public record AnswerSavedResponse(boolean saved) {}
