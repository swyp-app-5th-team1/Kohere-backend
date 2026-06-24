package com.kohere.diagnosis.infrastructure;

import com.kohere.diagnosis.domain.SuggestionCatalog;
import com.kohere.diagnosis.domain.SuggestionContent;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/** 추천 조정 제안 카탈로그 영속 어댑터. 도메인 포트 {@link SuggestionCatalog}를 MongoDB로 구현하고 도큐먼트↔도메인 값을 매핑한다. */
@Repository
@RequiredArgsConstructor
public class SuggestionCatalogImpl implements SuggestionCatalog {

  private final DiagnosisSuggestionMongoRepository mongoRepository;

  @Override
  public Optional<SuggestionContent> findByReason(String reason) {
    return mongoRepository.findById(reason).map(SuggestionCatalogImpl::toDomain);
  }

  private static SuggestionContent toDomain(DiagnosisSuggestionDocument d) {
    List<SuggestionContent.Action> actions =
        d.getActions() == null
            ? List.of()
            : d.getActions().stream()
                .map(a -> new SuggestionContent.Action(a.getType(), a.getDetail()))
                .toList();
    return new SuggestionContent(d.getId(), d.getMessage(), actions);
  }
}
