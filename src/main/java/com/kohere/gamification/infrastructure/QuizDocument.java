package com.kohere.gamification.infrastructure;

import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * 학습 퀴즈 카탈로그 MongoDB 도큐먼트({@code quizzes}). 표시 문자열({@code question}·{@code choices[].text}·{@code
 * explanation})은 언어-키 맵으로 임베드한다(ADR-0029). {@code correctChoice}는 언어 무관 채점 키(A~D), {@code active}는
 * 랜덤 풀 게이트다(ADR-0035). 제출 기록·포인트는 없다(무상태).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "quizzes")
@CompoundIndex(name = "active_idx", def = "{'active': 1}")
public class QuizDocument {

  @Id private Long id;
  private Map<String, String> question;
  private List<ChoiceSpec> choices;
  private String correctChoice;
  private Map<String, String> explanation;
  private boolean active;

  /** 보기(key=언어 무관 A~D, text=언어-키 맵). */
  @Getter
  @Setter
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder
  public static class ChoiceSpec {
    private String key;
    private Map<String, String> text;
  }
}
