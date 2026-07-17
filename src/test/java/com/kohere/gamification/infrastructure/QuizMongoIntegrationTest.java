package com.kohere.gamification.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

import com.kohere.gamification.application.GamificationService;
import com.kohere.gamification.application.dto.AnswerResultResponse;
import com.kohere.gamification.application.dto.RandomQuizResponse;
import com.kohere.gamification.domain.QuizNotFoundException;
import com.kohere.gamification.domain.TenantOnlyException;
import com.kohere.gamification.infrastructure.QuizDocument.ChoiceSpec;
import com.kohere.gamification.presentation.dto.AnswerQuizRequest;
import com.kohere.user.api.UserAccountService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 게이미피케이션 학습 퀴즈 서비스 슬라이스 테스트. 실제 MongoDB(Testcontainers)에 카탈로그를 직접 시드하고, 교차 모듈 포트({@code
 * UserAccountService})는 목으로 대체한다. Mongock(@ChangeUnit)은 슬라이스에서 비활성화한다(ADR-0032).
 */
@DataMongoTest
@Testcontainers
@TestPropertySource(properties = "mongock.enabled=false")
@Import({GamificationService.class, QuizRepositoryImpl.class})
class QuizMongoIntegrationTest {

  @Container @ServiceConnection static MongoDBContainer mongo = new MongoDBContainer("mongo:7.0");

  @Autowired GamificationService gamificationService;
  @Autowired QuizMongoRepository quizMongoRepository;

  @MockitoBean UserAccountService userAccountService;

  @BeforeEach
  void setUp() {
    quizMongoRepository.deleteAll();
    given(userAccountService.getUserType(anyLong())).willReturn("TENANT");
  }

  @Test
  @DisplayName("랜덤 퀴즈는 등록 국가 언어로 번역되고, 미지원 언어는 영어로 폴백한다")
  void randomQuizTranslation() {
    seedQuiz();

    given(userAccountService.getLanguage(10L)).willReturn("ko");
    RandomQuizResponse ko = gamificationService.getRandomQuiz(10L);
    assertThat(ko.quizId()).isEqualTo(4001L);
    assertThat(ko.question()).isEqualTo("전세 임차인 보호 제도는?");
    assertThat(ko.choices())
        .extracting(RandomQuizResponse.Choice::key)
        .containsExactly("A", "B", "C", "D");
    assertThat(ko.choices().get(0).text()).isEqualTo("확정일자");

    given(userAccountService.getLanguage(11L)).willReturn("ja");
    RandomQuizResponse fallback = gamificationService.getRandomQuiz(11L);
    assertThat(fallback.question()).isEqualTo("Which protects a jeonse tenant?");
    assertThat(fallback.choices().get(0).text()).isEqualTo("Fixed date");
  }

  @Test
  @DisplayName("정답·오답 모두 번역된 해설을 반환하고, 오답이면 정답 키도 함께 반환한다")
  void gradeAnswer() {
    seedQuiz();
    given(userAccountService.getLanguage(10L)).willReturn("ko");

    AnswerResultResponse correct =
        gamificationService.gradeAnswer(10L, 4001L, new AnswerQuizRequest("A"));
    assertThat(correct.correct()).isTrue();
    assertThat(correct.correctChoice()).isNull();
    assertThat(correct.explanation()).isEqualTo("확정일자를 받으면 보증금을 보호받습니다.");

    AnswerResultResponse wrong =
        gamificationService.gradeAnswer(10L, 4001L, new AnswerQuizRequest("B"));
    assertThat(wrong.correct()).isFalse();
    assertThat(wrong.correctChoice()).isEqualTo("A");
    assertThat(wrong.explanation()).isEqualTo("확정일자를 받으면 보증금을 보호받습니다.");
  }

  @Test
  @DisplayName("활성 퀴즈 풀이 비어 있으면 QUIZ_NOT_FOUND")
  void emptyPool() {
    assertThatThrownBy(() -> gamificationService.getRandomQuiz(10L))
        .isInstanceOf(QuizNotFoundException.class);
  }

  @Test
  @DisplayName("존재하지 않는 퀴즈 채점은 QUIZ_NOT_FOUND")
  void gradeMissingQuiz() {
    assertThatThrownBy(
            () -> gamificationService.gradeAnswer(10L, 9999L, new AnswerQuizRequest("A")))
        .isInstanceOf(QuizNotFoundException.class);
  }

  @Test
  @DisplayName("세입자가 아니면 403(TenantOnly)로 거부한다")
  void nonTenantRejected() {
    seedQuiz();
    given(userAccountService.getUserType(20L)).willReturn("LANDLORD");
    assertThatThrownBy(() -> gamificationService.getRandomQuiz(20L))
        .isInstanceOf(TenantOnlyException.class);
  }

  private void seedQuiz() {
    quizMongoRepository.save(
        QuizDocument.builder()
            .id(4001L)
            .active(true)
            .question(Map.of("en", "Which protects a jeonse tenant?", "ko", "전세 임차인 보호 제도는?"))
            .choices(
                List.of(
                    ChoiceSpec.builder()
                        .key("A")
                        .text(Map.of("en", "Fixed date", "ko", "확정일자"))
                        .build(),
                    ChoiceSpec.builder().key("B").text(Map.of("en", "Fee", "ko", "관리비")).build(),
                    ChoiceSpec.builder().key("C").text(Map.of("en", "Loan", "ko", "대출")).build(),
                    ChoiceSpec.builder().key("D").text(Map.of("en", "Tax", "ko", "세금")).build()))
            .correctChoice("A")
            .explanation(
                Map.of("en", "A fixed date protects your deposit.", "ko", "확정일자를 받으면 보증금을 보호받습니다."))
            .build());
  }
}
