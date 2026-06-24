package com.kohere.diagnosis.application;

import com.kohere.diagnosis.application.dto.RecommendationResponse.SuggestionAction;
import com.kohere.diagnosis.application.dto.RecommendationResponse.Suggestions;
import com.kohere.diagnosis.domain.SuggestionCatalog;
import com.kohere.diagnosis.domain.SuggestionContent;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 추천 0건 조정 제안(suggestions)을 카탈로그(MongoDB {@code diagnosisSuggestions})에서 받아 사용자 언어로 조립한다(ADR-0029).
 * {@code reason}/{@code type}은 언어 무관 식별 키이고, 사람이 보는 message/detail만 사용자 언어로 고르며 없으면 영어로 폴백한다.
 */
@Component
@RequiredArgsConstructor
public class SuggestionMessages {

  private static final String DEFAULT_LANGUAGE = "en";
  private static final String REASON_NO_MATCH = "NO_MATCH";

  private final SuggestionCatalog suggestionCatalog;

  /** 매칭 0건 제안을 사용자 언어로 구성한다(카탈로그에서 조회). */
  public Suggestions noMatch(String language) {
    SuggestionContent content =
        suggestionCatalog
            .findByReason(REASON_NO_MATCH)
            .orElseThrow(() -> new IllegalStateException("추천 제안 카탈로그가 없습니다: " + REASON_NO_MATCH));
    String message = pick(content.message(), language);
    List<SuggestionAction> actions =
        content.actions().stream()
            .map(a -> new SuggestionAction(a.type(), pick(a.detail(), language)))
            .toList();
    return new Suggestions(content.reason(), message, actions);
  }

  private static String pick(Map<String, String> map, String language) {
    if (map == null) {
      return null;
    }
    String value = map.get(language);
    if (value != null) {
      return value;
    }
    return map.getOrDefault(DEFAULT_LANGUAGE, map.values().stream().findFirst().orElse(null));
  }
}
