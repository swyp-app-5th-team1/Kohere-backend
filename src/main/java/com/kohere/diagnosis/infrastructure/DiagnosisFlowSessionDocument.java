package com.kohere.diagnosis.infrastructure;

import com.kohere.diagnosis.domain.ArcStatus;
import com.kohere.diagnosis.domain.DiagnosisCondition;
import com.kohere.diagnosis.domain.District;
import com.kohere.diagnosis.domain.Purpose;
import com.kohere.diagnosis.domain.Region;
import com.kohere.diagnosis.domain.UniversityGroup;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * v2 진행 세션 MongoDB 영속 도큐먼트({@code diagnosisFlowSessions}, issue #157·ADR-0036). 도메인 {@code
 * DiagnosisFlowSession}과 분리된 영속 전용 타입이며 어댑터가 도메인↔도큐먼트를 매핑한다. enum은 이름 문자열로 저장된다.
 *
 * <p>{@code userId} UNIQUE 인덱스(사용자당 1 세션)는 {@link DiagnosisFlowSessionIndexInitializer}가 기동 시 멱등
 * 생성한다(Spring Boot 3.5 자동 인덱스 비활성, migration-policy §8).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "diagnosisFlowSessions")
public class DiagnosisFlowSessionDocument {

  @Id private String id;

  @Indexed(unique = true)
  private Long userId;

  private DraftDocument draft;

  /** 서버가 직전에 낸 문항의 field — 다음 답의 기대값이자 진행 위치의 단일 정본(정본 슬롯 문항·예외질문 공통). */
  private String pendingField;

  /** 누적 답 스냅샷(진행 중 초안의 답 필드만; status/id는 세션 레벨에서 유도). */
  @Getter
  @Setter
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder
  public static class DraftDocument {
    private Region region;
    private Purpose purpose;
    private UniversityGroup university;
    private District district;
    private Set<DiagnosisCondition> conditions;
    private Integer monthlyRentMin;
    private Integer monthlyRentMax;
    private ArcStatus arcStatus;
  }
}
