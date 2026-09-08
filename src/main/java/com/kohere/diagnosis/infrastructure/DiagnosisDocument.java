package com.kohere.diagnosis.infrastructure;

import com.kohere.diagnosis.domain.ArcStatus;
import com.kohere.diagnosis.domain.DiagnosisStatus;
import com.kohere.diagnosis.domain.District;
import com.kohere.diagnosis.domain.Purpose;
import com.kohere.diagnosis.domain.Region;
import com.kohere.diagnosis.domain.UniversityGroup;
import java.time.Instant;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * 진단 MongoDB 영속 도큐먼트({@code diagnoses}). 도메인 {@code Diagnosis}와 분리된 영속 전용 타입이며 어댑터가 도메인↔도큐먼트를
 * 매핑한다(JPA 어댑터 패턴을 MongoDB로 옮긴 형태). enum은 이름 문자열로 저장된다.
 *
 * <p>인덱스: {@code (userId, submittedAt desc)}(이력·최근 진단 조회). 사용자당 IN_PROGRESS 1건은 응용 로직으로 보장한다.
 *
 * <p>신원은 {@code userId}(회원)와 {@code guestSessionId}(게스트) 중 <b>정확히 하나</b>만 채워진다(#181). 게스트 문서를 만드는
 * 경로는 v2 흐름뿐이고 종료 상태({@code COMPLETED}·{@code DISCARDED})로만 저장되므로 게스트 문서에는 {@code IN_PROGRESS}가
 * 없다(v2 진행 상태는 {@code diagnosisFlowSessions}에 있다). 이력·최근 조회는 {@code userId}로 질의해 게스트 문서가 애초에 걸리지
 * 않는다.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "diagnoses")
@CompoundIndex(name = "userId_submittedAt_idx", def = "{'userId': 1, 'submittedAt': -1}")
public class DiagnosisDocument {

  @Id private Long id;
  @Indexed private Long userId;

  /**
   * 게스트 진단의 신원({@code anonymous<uuid>}, 회원 진단은 null) — {@code userId}와 정확히 하나만 채워진다(#181). 인덱스를 두지
   * 않는다: 게스트 진단은 v2 추천 조회가 {@code _id}로만 찾고, 이력·최근은 회원 전용이라 이 필드로 질의하지 않는다.
   */
  private String guestSessionId;

  private Region region;
  private Purpose purpose;
  private UniversityGroup university;
  private District district;

  /**
   * ④ 주거 조건 코드. <b>도메인 enum이 아니라 문자열로 든다</b> — 삭제된 enum 상수가 든 문서가 실재해서(0004가 백필한 {@code NO_ARC}),
   * enum으로 선언하면 문서 매핑 단계의 {@code Enum.valueOf}가 던져 조회가 통째로 500이 된다. 문자열↔도메인 변환과 미등록 코드 폐기는 {@link
   * DiagnosisConditionCodes}가 한 벌로 맡는다. 저장 모양은 enum을 그대로 쓰던 때와 동일하다.
   */
  private Set<String> conditions;

  private Integer monthlyRentMin;
  private Integer monthlyRentMax;
  private ArcStatus arcStatus;
  private DiagnosisStatus status;

  /** 종료 시각. {@code COMPLETED}는 제출 확정 시각, {@code DISCARDED}는 폐기 시각. */
  private Instant submittedAt;
}
