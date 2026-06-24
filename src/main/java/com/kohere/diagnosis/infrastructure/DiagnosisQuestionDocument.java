package com.kohere.diagnosis.infrastructure;

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
 * 진단 문항 카탈로그 MongoDB 도큐먼트({@code diagnosisQuestions}). 데이터만 보유(분기 메타 없음)하며 표시 문자열은 언어-키 맵으로 임베드한다
 * (ADR-0028·ADR-0029). 단계 3은 {@code field=university}/{@code district} 두 도큐먼트로 존재하고 응용 서비스가 {@code
 * purpose}로 택일한다.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "diagnosisQuestions")
@CompoundIndex(name = "active_step_idx", def = "{'active': 1, 'step': 1}")
public class DiagnosisQuestionDocument {

  @Id private String id;
  private int step;
  private String field;
  private Map<String, String> question;
  private SelectSpec select;
  private List<OptionSpec> options;
  private boolean active;

  /** 선택 제약(type=SINGLE/MULTI/NUMBER, max). */
  @Getter
  @Setter
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder
  public static class SelectSpec {
    private String type;
    private int max;
  }

  /** 선택지(code=언어 무관 제출 enum, label=언어-키 맵). */
  @Getter
  @Setter
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder
  public static class OptionSpec {
    private String code;
    private Map<String, String> label;
  }
}
