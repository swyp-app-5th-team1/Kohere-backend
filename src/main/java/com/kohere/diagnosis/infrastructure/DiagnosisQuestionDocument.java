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
 * 진단 문항 카탈로그 MongoDB 도큐먼트({@code diagnosisQuestions}). 데이터만 보유(분기 메타 없음)하며 표시 문자열은 언어-키 맵으로 임베드한다
 * (ADR-0028·ADR-0029).
 *
 * <p><b>순서·단계 번호를 담지 않는다</b> — 진행 순서와 step은 코드({@code DiagnosisFlowStep})가 정본이고, 여기는 문항의 표현만 둔다
 * (ADR-0036). 문항은 {@code field}로 유일하게 식별된다({@code field} UNIQUE 인덱스 — {@link
 * DiagnosisQuestionIndexInitializer}). ③ 대학/지역처럼 한 단계에 문항이 둘이어도 각자 다른 {@code field}라 그대로 구분된다.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "diagnosisQuestions")
public class DiagnosisQuestionDocument {

  @Id private String id;

  /** 문항 식별자 겸 제출 필드명(카탈로그 전체에서 유일). */
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
