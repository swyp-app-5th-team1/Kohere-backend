package com.kohere.diagnosis.domain;

import java.util.List;
import java.util.Map;

/**
 * 진단 문항 카탈로그 항목(도메인 값). 표시 문자열(question·option label)은 언어 코드를 키로 하는 인라인 맵으로 보유하며, 응용 계층이 사용자 언어로 골라
 * 표시 DTO를 조립한다(ADR-0028·ADR-0029). 선택지 {@code code}는 언어 무관 제출 enum 값이다.
 *
 * <p><b>카탈로그는 문항의 표현만 담고 순서는 담지 않는다</b> — 문항은 {@code field}로 유일하게 식별되며, 진행 순서와 단계 번호(step)는
 * 코드({@code DiagnosisFlowStep})가 정본이다(ADR-0036). 그래서 여기 {@code step}이 없다.
 *
 * @param field 문항 식별자 겸 제출
 *     필드명(region/regionRetry/purpose/university/district/conditions/monthlyRent/arcStatus)
 * @param question 언어-키 맵 문항(예: {@code {"en": "...", "ko": "..."}})
 * @param select 선택 제약(type=SINGLE/MULTI/NUMBER, max)
 * @param options 선택지 목록(code + 언어-키 맵 label). 숫자 입력 단계는 빈 목록
 */
public record DiagnosisQuestion(
    String field, Map<String, String> question, Select select, List<Option> options) {

  /** 선택 제약. {@code type}=SINGLE/MULTI(선택지)·NUMBER(숫자 입력), {@code max}=최대 선택 개수. */
  public record Select(String type, int max) {}

  /** 선택지. {@code code}=언어 무관 제출 enum 값, {@code label}=언어-키 맵 표시 라벨. */
  public record Option(String code, Map<String, String> label) {}
}
