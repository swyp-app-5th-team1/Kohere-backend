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
 * district}가 채워진다(반대 필드는 null). 확정 시 6단계 필수·조건부 대학(그룹)/지역·조건 최대 3·월세 범위(min≤max) 불변식을 강제한다.
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
  private final UniversityGroup university;
  private final District district;
  private final Set<DiagnosisCondition> conditions;
  private final Integer monthlyRentMin;
  private final Integer monthlyRentMax;
  private final ArcStatus arcStatus;
  private final DiagnosisStatus status;

  /**
   * 종료 시각(UTC). {@code COMPLETED}는 제출 확정 시각, {@code DISCARDED}는 폐기 시각({@code IN_PROGRESS}에는 부재).
   */
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

  /**
   * 6단계를 채우지 못하고 끝난 시도를 폐기 기록으로 남긴다(`IN_PROGRESS → DISCARDED`). v2 흐름이 재시도({@code
   * RESTART})·종료({@code TERMINATED})·이탈(다음 {@code /start}가 덮어씀)로 끝날 때 부분 답을 그대로 보존한다 — "어느 지역을 원했는데
   * 매물이 없었나" 같은 수요 신호를 버리지 않기 위해서다(ADR-0036).
   *
   * <p><b>완결성을 검증하지 않는다</b> — 부분 답이 정상이기 때문이다({@link #complete}와의 유일한 차이). 대신 이 상태는 사용자에게 노출하지 않는다
   * — 목록(이력·최근)은 {@code COMPLETED}만 보고, id로 직접 오는 상세·추천은 응용 계층이 명시적으로 404로 거절한다(ADR-0036 결정 12).
   *
   * <p>{@code submittedAt}에 폐기 시각을 담는다 — 별도 타임스탬프 필드를 두는 대신 "종료 시각"으로 통일한다(상태가 어느 종료인지 이미 말해준다).
   */
  public Diagnosis discard(Instant now) {
    return toBuilder().status(DiagnosisStatus.DISCARDED).submittedAt(now).build();
  }

  /** 확정 전 저장된 답의 완결성·정합성을 검증한다(6단계 필수·조건부 대학(그룹)/지역·조건 최대 3·월세 범위 min≤max). */
  public void validateComplete() {
    if (region == null) {
      throw new InvalidInputException("region 답변이 필요합니다.");
    }
    if (purpose == null) {
      throw new InvalidInputException("purpose 답변이 필요합니다.");
    }
    validatePurposeBranch();
    // NO_ARC는 ⑥ arcStatus에서 파생한 매물 정책 필터라 ④ 최대 3개 제한 계산에서 제외한다.
    if (conditions != null
        && conditions.stream().filter(DiagnosisCondition::userSelectable).count()
            > MAX_CONDITIONS) {
      throw new InvalidInputException("conditions는 최대 3개까지 선택할 수 있습니다.");
    }
    if (monthlyRentMin == null || monthlyRentMax == null) {
      throw new InvalidInputException("monthlyRent 답변이 필요합니다.");
    }
    if (monthlyRentMin < 0 || monthlyRentMax < 0) {
      throw new InvalidInputException("0 이상이어야 합니다.");
    }
    if (monthlyRentMin > monthlyRentMax) {
      throw new InvalidInputException("monthlyRentMin은 monthlyRentMax 이하여야 합니다.");
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
