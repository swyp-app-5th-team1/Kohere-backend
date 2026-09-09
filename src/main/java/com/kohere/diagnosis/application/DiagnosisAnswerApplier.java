package com.kohere.diagnosis.application;

import com.kohere.common.exception.InvalidInputException;
import com.kohere.diagnosis.domain.ArcStatus;
import com.kohere.diagnosis.domain.Diagnosis;
import com.kohere.diagnosis.domain.DiagnosisCondition;
import com.kohere.diagnosis.domain.District;
import com.kohere.diagnosis.domain.Purpose;
import com.kohere.diagnosis.domain.Region;
import com.kohere.diagnosis.domain.UniversityGroup;
import com.kohere.diagnosis.presentation.dto.AnswerRequest;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 단계별 답(field+code/codes/min-max)을 파싱·검증해 진행 중 진단 초안에 적용한다. 서버 주도 흐름({@code POST
 * /api/v2/diagnoses/next})이 쓰는 순수 컴포넌트로, 분기·파생 규칙을 한 곳에 모아 둔다. 잘못된 답은 공통 {@code
 * INVALID_INPUT}(400)으로 막는다.
 *
 * <p>docs/api/specs/02-diagnosis-recommendation.md · ADR-0036.
 */
@Component
public class DiagnosisAnswerApplier {

  /** 단일 선택 답을 싣는 요청 본문 필드명({@code AnswerRequest.code}) — {@code errors[].field}로 나간다. */
  private static final String CODE = "code";

  /** 다중 선택 답을 싣는 요청 본문 필드명({@code AnswerRequest.codes}) — {@code errors[].field}로 나간다. */
  private static final String CODES = "codes";

  /** 현재 답 1개를 초안에 적용한 새 초안을 반환한다(불변 애그리거트 전이). */
  Diagnosis apply(Diagnosis draft, AnswerRequest request) {
    String field = request.field();
    if (field == null || field.isBlank()) {
      throw new InvalidInputException("field", "validation.required");
    }
    Diagnosis.DiagnosisBuilder builder = draft.toBuilder();
    switch (field) {
      case "region" -> builder.region(parseEnum(Region.class, CODE, requireCode(request)));
      case "purpose" ->
          builder
              .purpose(parseEnum(Purpose.class, CODE, requireCode(request)))
              .university(null)
              .district(null);
      case "university" -> {
        requirePurpose(draft, Purpose.STUDY);
        builder
            .university(parseEnum(UniversityGroup.class, CODE, requireCode(request)))
            .district(null);
      }
      case "district" -> {
        requirePurpose(draft, Purpose.NON_STUDY);
        builder.district(parseEnum(District.class, CODE, requireCode(request))).university(null);
      }
      case "conditions" -> builder.conditions(parseConditions(request));
      case "monthlyRent" -> {
        validateRent(request);
        builder.monthlyRentMin(request.min()).monthlyRentMax(request.max());
      }
      case "arcStatus" -> {
        ArcStatus arcStatus = parseEnum(ArcStatus.class, CODE, requireCode(request));
        builder.arcStatus(arcStatus);
      }
      default -> throw new InvalidInputException("field", "validation.notAllowed", field);
    }
    return builder.build();
  }

  private static String requireCode(AnswerRequest request) {
    if (request.code() == null || request.code().isBlank()) {
      throw new InvalidInputException(CODE, "validation.required");
    }
    return request.code();
  }

  private static void requirePurpose(Diagnosis draft, Purpose expected) {
    if (draft.getPurpose() != expected) {
      throw new InvalidInputException("field", "validation.onlyForPurpose", expected.name());
    }
  }

  private static Set<DiagnosisCondition> parseConditions(AnswerRequest request) {
    Set<DiagnosisCondition> result = new LinkedHashSet<>();
    if (request.codes() != null) {
      for (String code : request.codes()) {
        DiagnosisCondition condition = parseEnum(DiagnosisCondition.class, CODES, code);
        result.add(condition);
      }
    }
    if (result.size() > Diagnosis.MAX_CONDITIONS) {
      throw new InvalidInputException(
          CODES, "validation.maxSelections", Diagnosis.MAX_CONDITIONS, result.size());
    }
    return result;
  }

  private static void validateRent(AnswerRequest request) {
    // 두 필드를 따로 검사한다 — 「min·max 중 하나」로 묶으면 어느 쪽이 잘못됐는지 errors[]에 실을 수 없다.
    // 거절되는 요청 집합은 묶어서 검사할 때와 같다.
    if (request.min() == null) {
      throw new InvalidInputException("min", "validation.requiredForStep", "monthlyRent");
    }
    if (request.max() == null) {
      throw new InvalidInputException("max", "validation.requiredForStep", "monthlyRent");
    }
    if (request.min() < 0) {
      throw new InvalidInputException("min", "validation.min", 0, request.min());
    }
    if (request.max() < 0) {
      throw new InvalidInputException("max", "validation.min", 0, request.max());
    }
    if (request.min() > request.max()) {
      throw new InvalidInputException(
          "min", "validation.notGreaterThan", "max", request.min(), request.max());
    }
  }

  /**
   * @param field 값을 실어 보낸 요청 본문 필드명({@code code} 또는 {@code codes}) — {@code errors[].field}로 나간다
   */
  private static <E extends Enum<E>> E parseEnum(Class<E> type, String field, String value) {
    try {
      return Enum.valueOf(type, value);
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new InvalidInputException(field, "validation.notAllowed", value);
    }
  }
}
