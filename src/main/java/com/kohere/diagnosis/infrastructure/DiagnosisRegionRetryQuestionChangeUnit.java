package com.kohere.diagnosis.infrastructure;

import static com.kohere.diagnosis.infrastructure.DiagnosisCatalogSeedChangeUnit.REGION_RETRY_FIELD;
import static org.springframework.data.mongodb.core.query.Criteria.where;

import com.kohere.diagnosis.infrastructure.DiagnosisQuestionDocument.OptionSpec;
import com.kohere.diagnosis.infrastructure.DiagnosisQuestionDocument.SelectSpec;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import java.util.List;
import java.util.Map;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

/**
 * ① 지역 0건 예외질문("다른 지역 방을 찾아보시겠어요?")을 문항 카탈로그({@code diagnosisQuestions})에 적재한다(order 0005).
 *
 * <p>v2 흐름은 이 문구를 서버 코드에 하드코딩한 합성 질문이 아니라 <b>카탈로그의 일반 문항</b>으로 다룬다(ADR-0036) — 예외질문도 채팅 질문의 일부라
 * 문항·번역 정본을 다른 6단계와 같은 곳에 둔다. 이미 배포된 환경은 order 0000 시드가 이 문항 없이 실행됐으므로 여기서 추가한다. 신규 환경은 0000 시드가 이미
 * 적재하므로 기존 문항을 지우고 같은 내용을 다시 넣어 결과가 같다(멱등).
 *
 * <p>카탈로그는 순서를 담지 않고 {@code field}로 문항을 식별하므로 다른 문항과 동등하게 들어간다 — 이 문항이 정본 6슬롯 밖(흐름 제어)이라는 사실은 코드에만
 * 있다. 그 답({@code YES}/{@code NO})은 진단 답 필드가 아니라 흐름 제어 응답이므로 {@code Diagnosis}의 어떤 필드로도 저장되지 않는다 —
 * 다만 그 응답을 계기로 그때까지의 부분 답이 {@code DISCARDED} 진단으로 남는다(지역 수요 신호).
 */
@ChangeUnit(id = "diagnosis-region-retry-question", order = "0005", author = "kohere")
public class DiagnosisRegionRetryQuestionChangeUnit {

  @Execution
  public void execution(MongoTemplate mongo) {
    mongo.remove(new Query(where("field").is(REGION_RETRY_FIELD)), DiagnosisQuestionDocument.class);
    mongo.insert(regionRetryQuestion());
  }

  private static DiagnosisQuestionDocument regionRetryQuestion() {
    return DiagnosisQuestionDocument.builder()
        .field(REGION_RETRY_FIELD)
        .question(
            Map.of(
                "ko",
                "현재 지역에는 매물이 없어요. 다른 지역 방을 찾아보시겠어요?",
                "en",
                "There are no rooms in this region yet. Would you like to look in another region?"))
        .select(SelectSpec.builder().type("SINGLE").max(1).build())
        .options(List.of(option("YES", "예", "Yes"), option("NO", "아니오", "No")))
        .active(true)
        .build();
  }

  private static OptionSpec option(String code, String ko, String en) {
    return OptionSpec.builder().code(code).label(Map.of("ko", ko, "en", en)).build();
  }

  /** forward-only: 카탈로그 적재는 되돌리지 않는다(Mongock이 메서드 존재만 요구). */
  @RollbackExecution
  public void rollback() {
    // no-op (forward-only)
  }
}
