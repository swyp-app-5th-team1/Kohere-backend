package com.kohere.diagnosis.infrastructure;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/** MongoDB 원자적 카운터로 Long 순번 id를 발급한다(스펙의 숫자 {@code diagnosisId} 유지). */
@Component
@RequiredArgsConstructor
class SequenceGenerator {

  private final MongoOperations mongoOperations;

  /** 주어진 키의 다음 순번을 원자적으로 발급한다(없으면 upsert 후 1부터). */
  long nextId(String key) {
    DiagnosisSequence sequence =
        mongoOperations.findAndModify(
            query(where("_id").is(key)),
            new Update().inc("seq", 1L),
            FindAndModifyOptions.options().returnNew(true).upsert(true),
            DiagnosisSequence.class);
    return sequence == null ? 1L : sequence.getSeq();
  }
}
