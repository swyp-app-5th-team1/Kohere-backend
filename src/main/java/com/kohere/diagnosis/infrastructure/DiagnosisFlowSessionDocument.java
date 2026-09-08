package com.kohere.diagnosis.infrastructure;

import com.kohere.diagnosis.domain.ArcStatus;
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
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * v2 진행 세션 MongoDB 영속 도큐먼트({@code diagnosisFlowSessions}, issue #157·ADR-0036). 도메인 {@code
 * DiagnosisFlowSession}과 분리된 영속 전용 타입이며 어댑터가 도메인↔도큐먼트를 매핑한다. enum은 이름 문자열로 저장된다.
 *
 * <p>신원은 {@code userId}(회원)와 {@code guestSessionId}(게스트) 중 <b>정확히 하나</b>만 채워진다(#181). 이 불변식은 앱 레벨로만
 * 강제한다 — 이 컬렉션에는 {@code $jsonSchema} validator가 없다(ADR-0005 D7).
 *
 * <p>UNIQUE 인덱스는 신원별 <b>partial</b> 둘로 나뉜다("사용자당 1 세션" / "게스트 키당 1 세션"). 하나로 합치면 게스트 문서들의 {@code
 * userId} 부재가 서로 충돌해 두 번째 게스트부터 세션을 만들 수 없다. 생성은 {@link DiagnosisFlowSessionIndexInitializer}가 기동 시
 * 담당한다(Spring Boot 3.5 자동 인덱스 비활성, migration-policy §8).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "diagnosisFlowSessions")
public class DiagnosisFlowSessionDocument {

  @Id private String id;

  /**
   * 회원 세션의 신원(게스트 세션은 null). UNIQUE는 {@code @Indexed}로 선언하지 않는다 — 실제 인덱스가 partial이라 어노테이션으로 표현할 수
   * 없고, 선언과 실물이 어긋나면 안 된다. 정본은 {@link DiagnosisFlowSessionIndexInitializer}다.
   */
  private Long userId;

  /** 게스트 세션의 신원({@code anonymous<uuid>}, 회원 세션은 null). partial UNIQUE는 initializer가 만든다. */
  private String guestSessionId;

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

    /** 조건 코드 문자열. 정본 진단 문서와 같은 이유로 enum이 아니다({@link DiagnosisConditionCodes}). */
    private Set<String> conditions;

    private Integer monthlyRentMin;
    private Integer monthlyRentMax;
    private ArcStatus arcStatus;
  }
}
