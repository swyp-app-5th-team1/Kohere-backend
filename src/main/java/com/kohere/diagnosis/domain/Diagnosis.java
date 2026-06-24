package com.kohere.diagnosis.domain;

import com.kohere.common.exception.InvalidInputException;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.Builder;
import lombok.Getter;

/**
 * 진단 애그리거트 루트. 사용자가 단계별로 입력한 진단 답을 보관한다. 영속 기술(MongoDB)에 의존하지 않는 순수 도메인 모델이며, 영속 매핑은 infrastructure
 * 계층의 어댑터가 처리한다(docs/convention/code-style.md §3-3).
 *
 * <p>서버 stateful 진단: 사용자당 진행 중(IN_PROGRESS) 진단 1건에 단계별 답을 채워 가고, 확정 시 {@link #complete(Instant)}로
 * COMPLETED로 전이한다. 입국 목적이 {@code STUDY}이면 {@code university}가, {@code NON_STUDY}이면 {@code
 * district}가 채워진다(반대 필드는 null). 확정 시 6단계 필수·조건부 대학/지역·조건 최대 3·예산 0 이상 불변식을 강제한다.
 *
 * <p>스펙: docs/api/specs/02-diagnosis-recommendation.md · 시퀀스 US-2-1/US-2-5.
 */
@Getter
@Builder(toBuilder = true)
public class Diagnosis {

  /** 진단 조건(conditions) 최대 선택 개수. */
  public static final int MAX_CONDITIONS = 3;

  private final Long id;
  private final Long userId;
  private final Region region;
  private final Purpose purpose;
  private final University university;
  private final District district;
  private final Set<DiagnosisCondition> conditions;
  private final Integer monthlyBudgetMax;
  private final ArcStatus arcStatus;
  private final DiagnosisStatus status;
  private final Instant submittedAt;

  /** 사용자당 1건의 진행 중(IN_PROGRESS) 진단 초안을 시작한다. id는 영속 시 부여한다. */
  public static Diagnosis startInProgress(Long userId) {
    return Diagnosis.builder()
        .userId(userId)
        .conditions(new LinkedHashSet<>())
        .status(DiagnosisStatus.IN_PROGRESS)
        .build();
  }

  /**
   * 진행 중 초안을 확정(COMPLETED)한다. 저장된 답을 재검증하고 위반 시 {@code INVALID_INPUT}(400)으로 막는다. 재진단은 새 초안에서 다시
   * 호출되어 새 레코드가 되며 기존 진단을 덮어쓰지 않는다.
   */
  public Diagnosis complete(Instant now) {
    validateComplete();
    return toBuilder().status(DiagnosisStatus.COMPLETED).submittedAt(now).build();
  }

  /** 확정 전 저장된 답의 완결성·정합성을 검증한다(6단계 필수·조건부 대학/지역·조건 최대 3·예산 0 이상). */
  public void validateComplete() {
    if (region == null) {
      throw new InvalidInputException("region 답변이 필요합니다.");
    }
    if (purpose == null) {
      throw new InvalidInputException("purpose 답변이 필요합니다.");
    }
    validatePurposeBranch();
    if (conditions != null && conditions.size() > MAX_CONDITIONS) {
      throw new InvalidInputException("conditions는 최대 3개까지 선택할 수 있습니다.");
    }
    if (monthlyBudgetMax == null) {
      throw new InvalidInputException("monthlyBudgetMax 답변이 필요합니다.");
    }
    if (monthlyBudgetMax < 0) {
      throw new InvalidInputException("monthlyBudgetMax는 0 이상이어야 합니다.");
    }
    if (arcStatus == null) {
      throw new InvalidInputException("arcStatus 답변이 필요합니다.");
    }
  }

  /** 입국 목적 분기: STUDY면 university 필수·district 없음, NON_STUDY면 district 필수·university 없음. */
  private void validatePurposeBranch() {
    if (purpose == Purpose.STUDY) {
      if (university == null) {
        throw new InvalidInputException("STUDY는 university가 필요합니다.");
      }
      if (district != null) {
        throw new InvalidInputException("STUDY는 district를 가질 수 없습니다.");
      }
    } else if (purpose == Purpose.NON_STUDY) {
      if (district == null) {
        throw new InvalidInputException("NON_STUDY는 district가 필요합니다.");
      }
      if (university != null) {
        throw new InvalidInputException("NON_STUDY는 university를 가질 수 없습니다.");
      }
    }
  }
}
