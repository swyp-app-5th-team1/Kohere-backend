package com.kohere.diagnosis.infrastructure;

import com.kohere.diagnosis.domain.DiagnosisQuestion;
import com.kohere.diagnosis.domain.DiagnosisQuestionCatalog;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/** 진단 문항 카탈로그 영속 어댑터. 도메인 포트 {@link DiagnosisQuestionCatalog}를 MongoDB로 구현하고 도큐먼트↔도메인 값을 매핑한다. */
@Repository
@RequiredArgsConstructor
public class DiagnosisQuestionCatalogImpl implements DiagnosisQuestionCatalog {

  private final DiagnosisQuestionMongoRepository mongoRepository;

  @Override
  public Optional<DiagnosisQuestion> findByField(String field) {
    return mongoRepository
        .findByFieldAndActiveTrue(field)
        .map(DiagnosisQuestionCatalogImpl::toDomain);
  }

  private static DiagnosisQuestion toDomain(DiagnosisQuestionDocument d) {
    List<DiagnosisQuestion.Option> options =
        d.getOptions() == null
            ? List.of()
            : d.getOptions().stream()
                .map(o -> new DiagnosisQuestion.Option(o.getCode(), o.getLabel()))
                .toList();
    DiagnosisQuestion.Select select =
        new DiagnosisQuestion.Select(d.getSelect().getType(), d.getSelect().getMax());
    return new DiagnosisQuestion(d.getField(), d.getQuestion(), select, options);
  }
}
