package com.kohere.diagnosis.infrastructure;

import com.kohere.diagnosis.domain.ArcStatus;
import com.kohere.diagnosis.domain.DiagnosisCondition;
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
  private Region region;
  private Purpose purpose;
  private UniversityGroup university;
  private District district;
  private Set<DiagnosisCondition> conditions;
  private Integer monthlyRentMin;
  private Integer monthlyRentMax;
  private ArcStatus arcStatus;
  private DiagnosisStatus status;

  /** 종료 시각. {@code COMPLETED}는 제출 확정 시각, {@code DISCARDED}는 폐기 시각. */
  private Instant submittedAt;
}
