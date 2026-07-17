package com.kohere.gamification.application;

import com.kohere.gamification.application.dto.AnswerResultResponse;
import com.kohere.gamification.application.dto.RandomQuizResponse;
import com.kohere.gamification.domain.ChoiceKey;
import com.kohere.gamification.domain.Quiz;
import com.kohere.gamification.domain.QuizNotFoundException;
import com.kohere.gamification.domain.QuizRepository;
import com.kohere.gamification.domain.TenantOnlyException;
import com.kohere.gamification.presentation.dto.AnswerQuizRequest;
import com.kohere.user.api.UserAccountService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 게이미피케이션(학습 퀴즈) 유스케이스. 무상태 — 요청마다 활성 퀴즈 풀에서 무작위 1개를 사용자 언어로 번역해 조회하고, 제출한 보기를 저장된 정답과 대조해 즉시
 * 채점한다(제출 기록·포인트 없음, ADR-0035). 대상은 외국인 세입자(userType=TENANT, ACTIVE)다.
 *
 * <p>표시 언어는 {@code user} 공개 query {@code getLanguage}로 취득하며(등록 국가 → {@code countries.lang}), 해당 언어
 * 번역이 없으면 영어({@code en})로 폴백한다(ADR-0029). 보기 키 A~D는 언어와 무관하고 채점은 키로 수행한다. 의존성은 생성자 주입으로 받는다(§3-4).
 */
@Service
@RequiredArgsConstructor
public class GamificationService {

  /** 표시 문자열 언어-키 맵에서 사용자 언어 값이 없을 때의 폴백 언어(ADR-0029). */
  private static final String DEFAULT_LANGUAGE = "en";

  /** 세입자 전용 게이트 기준 userType(ADR-0035). */
  private static final String USER_TYPE_TENANT = "TENANT";

  private final QuizRepository quizRepository;
  private final UserAccountService userAccountService;

  /** 활성 퀴즈 풀에서 무작위 1개를 사용자 언어로 번역해 조회한다(정답·해설 제외). */
  public RandomQuizResponse getRandomQuiz(long userId) {
    assertTenant(userId);
    Quiz quiz = quizRepository.findRandomActive().orElseThrow(QuizNotFoundException::new);
    String language = resolveLanguage(userId);
    List<RandomQuizResponse.Choice> choices =
        quiz.choices().stream()
            .map(c -> new RandomQuizResponse.Choice(c.key().name(), pickLabel(c.text(), language)))
            .toList();
    return new RandomQuizResponse(quiz.id(), pickLabel(quiz.question(), language), choices);
  }

  /** 제출한 보기를 저장된 정답과 대조해 채점한다(무상태). 정답·오답 모두 해설(번역)을, 오답이면 정답 키도 함께 반환한다. */
  public AnswerResultResponse gradeAnswer(long userId, long quizId, AnswerQuizRequest request) {
    assertTenant(userId);
    Quiz quiz = quizRepository.findById(quizId).orElseThrow(QuizNotFoundException::new);
    ChoiceKey selected = ChoiceKey.valueOf(request.selectedChoice());
    String explanation = pickLabel(quiz.explanation(), resolveLanguage(userId));
    if (quiz.isCorrect(selected)) {
      return new AnswerResultResponse(quiz.id(), selected.name(), true, null, explanation);
    }
    return new AnswerResultResponse(
        quiz.id(), selected.name(), false, quiz.correctChoice().name(), explanation);
  }

  /** 외국인 세입자(userType=TENANT) 전용 게이트. 임대인 등은 403(FORBIDDEN)으로 거부한다. */
  private void assertTenant(long userId) {
    if (!USER_TYPE_TENANT.equals(userAccountService.getUserType(userId))) {
      throw new TenantOnlyException();
    }
  }

  private String resolveLanguage(long userId) {
    return userAccountService.getLanguage(userId);
  }

  private static String pickLabel(Map<String, String> labels, String language) {
    if (labels == null) {
      return null;
    }
    String value = labels.get(language);
    if (value != null) {
      return value;
    }
    return labels.getOrDefault(DEFAULT_LANGUAGE, labels.values().stream().findFirst().orElse(null));
  }
}
