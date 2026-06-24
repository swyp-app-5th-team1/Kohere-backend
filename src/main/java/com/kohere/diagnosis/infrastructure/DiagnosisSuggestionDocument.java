package com.kohere.diagnosis.infrastructure;

import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * 추천 조정 제안 MongoDB 도큐먼트({@code diagnosisSuggestions}). 사유(reason)를 {@code _id}로, 표시 문자열을 언어-키 맵으로
 * 임베드한다(ADR-0028·ADR-0029, 문항 카탈로그와 동일 방식). {@code id}(reason)·{@code actions[].type}은 언어 무관 식별 키다.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "diagnosisSuggestions")
class DiagnosisSuggestionDocument {

  @Id private String id;
  private Map<String, String> message;
  private List<ActionSpec> actions;

  /** 조정 액션(type=언어 무관 식별 키, detail=언어-키 맵). */
  @Getter
  @Setter
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder
  static class ActionSpec {
    private String type;
    private Map<String, String> detail;
  }
}
