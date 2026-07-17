package com.kohere.diagnosis.domain;

import java.util.List;

/**
 * 진단 6단계의 <b>정본 선형 순서</b>와 슬롯↔field 매핑(issue #157·ADR-0036). 순서는 <b>선언 순서(ordinal)가 정본</b>이며 {@link
 * #next()}가 그 순서를 따라간다 — v2 서버 주도 흐름은 "방금 답한 문항의 다음 슬롯"으로 진행하고, 마지막 슬롯({@code ARC_STATUS})을 답하면 빌더
 * 완성이다.
 *
 * <p><b>이 enum이 순서의 유일한 정본이다</b> — 카탈로그({@code diagnosisQuestions})는 문구·선택지·번역만 담고 순서를 담지 않으며, 문항은
 * {@code field}로 조회한다. {@link #step()}(1..6)은 v1 {@code GET /questions/{step}}의 경로 파라미터를 {@code
 * field}로 옮기고({@link #ofStep(int)}) 응답에 표시하는 데만 쓰며, 위치에서 파생하므로 순서와 어긋날 수 없다.
 *
 * <p>{@code UNIVERSITY_OR_DISTRICT}(step 3)만 문항이 둘이라 {@link #field()}가 {@code null}이다 — 저장된 {@code
 * purpose}로 {@code university}/{@code district}를 서버가 택일한다(ADR-0028). {@link #ofField(String)}은 그 둘을
 * 모두 {@code UNIVERSITY_OR_DISTRICT}로 되돌린다.
 */
public enum DiagnosisFlowStep {
  REGION("region"),
  PURPOSE("purpose"),
  UNIVERSITY_OR_DISTRICT("university", "district"),
  CONDITIONS("conditions"),
  MONTHLY_RENT("monthlyRent"),
  ARC_STATUS("arcStatus");

  /** 이 슬롯이 받는 제출 필드(대부분 1개, {@code UNIVERSITY_OR_DISTRICT}만 purpose로 택일하는 2개). */
  private final List<String> fields;

  DiagnosisFlowStep(String... fields) {
    this.fields = List.of(fields);
  }

  /**
   * v1 {@code GET /questions/{step}}의 단계 번호(1..6)이자 응답의 표시값. <b>정본 순서에서의 위치를 그대로 파생</b>하므로 순서와 어긋날
   * 수 없다 — 별도 값으로 들고 있으면 "①~⑥ = N번째 질문"이라는 뜻이 깨진 채로도 컴파일된다.
   *
   * <p>진행에는 쓰이지 않는다({@link #next()}가 담당) — v1 경로 파라미터를 {@code field}로 옮기는 데({@link #ofStep(int)})와
   * 응답 표시에만 쓴다.
   */
  public int step() {
    return ordinal() + 1;
  }

  /** 제출 필드명. {@code UNIVERSITY_OR_DISTRICT}는 {@code null}(저장된 purpose로 런타임 택일). */
  public String field() {
    return fields.size() == 1 ? fields.get(0) : null;
  }

  /** 다음 슬롯. 마지막({@code ARC_STATUS})이면 {@code null} — 빌더 완성 → 자동 확정. */
  public DiagnosisFlowStep next() {
    int following = ordinal() + 1;
    DiagnosisFlowStep[] values = values();
    return following < values.length ? values[following] : null;
  }

  /**
   * 제출 필드가 속한 슬롯. 방금 답한 문항에서 다음 슬롯으로 진행할 때 쓴다 — {@code university}·{@code district}는 둘 다 {@code
   * UNIVERSITY_OR_DISTRICT}다.
   *
   * <p>정본 6슬롯에 없는 흐름 제어 필드({@code regionRetry})는 여기 없다 — 그 응답은 터미널이라 "다음 슬롯"을 물을 일이 없다.
   */
  public static DiagnosisFlowStep ofField(String field) {
    for (DiagnosisFlowStep slot : values()) {
      if (slot.fields.contains(field)) {
        return slot;
      }
    }
    throw new IllegalArgumentException("정본 6슬롯에 없는 field입니다: " + field);
  }

  /**
   * 단계 번호(1..6)의 슬롯 — {@link #step()}의 역이다. v1이 클라가 지정한 {@code step}의 문항 {@code field}를 고를 때 쓴다.
   */
  public static DiagnosisFlowStep ofStep(int step) {
    int index = step - 1;
    DiagnosisFlowStep[] values = values();
    if (index < 0 || index >= values.length) {
      throw new IllegalArgumentException("step이 범위를 벗어났습니다: " + step);
    }
    return values[index];
  }
}
