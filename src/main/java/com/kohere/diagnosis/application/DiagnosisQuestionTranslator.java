package com.kohere.diagnosis.application;

import com.kohere.common.exception.InvalidInputException;
import com.kohere.diagnosis.application.dto.QuestionResponse;
import com.kohere.diagnosis.domain.DiagnosisQuestion;
import com.kohere.diagnosis.domain.Purpose;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 진단 문항 카탈로그를 사용자 표시 언어로 조립·번역하고, ③(step 3) 대학/지역 분기 필드를 계산한다(US-2-5·US-2-6). v1 단계별 조회({@code GET
 * /questions/{step}})와 v2 서버 주도 흐름이 공유한다.
 *
 * <p>표시 문자열({@code question}·{@code label})만 언어별로 고르고 선택지 {@code code}는 언어 무관 불변이다. 사용자 언어 키가 없으면
 * 영어({@code en})로 폴백한다(ADR-0029).
 */
@Component
public class DiagnosisQuestionTranslator {

  /** 라벨·메시지 언어-키 맵에서 사용자 언어 값이 없을 때의 폴백 언어(ADR-0029). */
  private static final String DEFAULT_LANGUAGE = "en";

  /**
   * 카탈로그 문항 1개를 사용자 언어로 조립한 표시 DTO로 변환한다.
   *
   * <p>{@code step}은 카탈로그가 아니라 <b>호출자가 넘긴다</b> — 순서·단계 번호의 정본은 코드({@code DiagnosisFlowStep})이고
   * 카탈로그는 표현만 담기 때문이다(ADR-0036). ① 지역 0건 예외질문도 호출자가 지역 단계 번호를 실어준다.
   */
  QuestionResponse translate(DiagnosisQuestion question, int step, String language) {
    QuestionResponse.Select select =
        new QuestionResponse.Select(question.select().type(), question.select().max());
    List<QuestionResponse.Option> options =
        question.options().stream()
            .map(o -> new QuestionResponse.Option(o.code(), pickLabel(o.label(), language)))
            .toList();
    return new QuestionResponse(
        step, question.field(), pickLabel(question.question(), language), select, options);
  }

  /** ③ 분기: 저장된 purpose로 university/district 필드를 정한다(purpose 미선행이면 INVALID_INPUT). */
  String resolveBranchField(Purpose purpose) {
    if (purpose == null) {
      throw new InvalidInputException("purpose 답변이 선행되어야 합니다.");
    }
    return purpose == Purpose.STUDY ? "university" : "district";
  }

  /** 언어-키 맵에서 사용자 언어 값을 고르고, 없으면 영어(en)·그마저 없으면 첫 값으로 폴백한다. */
  String pickLabel(Map<String, String> labels, String language) {
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
