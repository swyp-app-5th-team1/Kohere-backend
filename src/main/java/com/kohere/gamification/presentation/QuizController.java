package com.kohere.gamification.presentation;

import com.kohere.common.response.ApiResponse;
import com.kohere.common.security.AuthPrincipal;
import com.kohere.gamification.application.GamificationService;
import com.kohere.gamification.application.dto.AnswerResultResponse;
import com.kohere.gamification.application.dto.RandomQuizResponse;
import com.kohere.gamification.presentation.dto.AnswerQuizRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 학습 퀴즈 REST 컨트롤러. 입력 바인딩·DTO 변환만 담당하고 비즈니스 로직은 응용 계층에 위임한다(docs/convention/code-style.md §3-3).
 * 응답은 공통 래퍼로 감싼다. 인증 주체(userId)는 {@code @AuthenticationPrincipal AuthPrincipal}에서 꺼낸다(ADR-0010).
 *
 * <p>외국인 세입자(ACTIVE) 전용이며 무상태다 — 랜덤 조회·즉시 채점만 하고 제출 기록·포인트가 없다(ADR-0035).
 *
 * <p>스펙: docs/api/specs/06-gamification.md.
 */
@RestController
@RequestMapping("/api/v1/quizzes")
@RequiredArgsConstructor
public class QuizController {

  private final GamificationService gamificationService;

  @GetMapping("/random")
  public ApiResponse<RandomQuizResponse> getRandom(
      @AuthenticationPrincipal AuthPrincipal principal) {
    return ApiResponse.success(gamificationService.getRandomQuiz(principal.userId()));
  }

  @PostMapping("/{quizId}/answer")
  public ApiResponse<AnswerResultResponse> answer(
      @AuthenticationPrincipal AuthPrincipal principal,
      @PathVariable Long quizId,
      @Valid @RequestBody AnswerQuizRequest request) {
    return ApiResponse.success(
        gamificationService.gradeAnswer(principal.userId(), quizId, request));
  }
}
