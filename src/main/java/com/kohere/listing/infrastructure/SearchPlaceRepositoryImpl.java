package com.kohere.listing.infrastructure;

import com.kohere.listing.domain.SearchPlace;
import com.kohere.listing.domain.SearchPlaceRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/** MongoDB searchPlaces 컬렉션을 도메인 포트 {@link SearchPlaceRepository}로 연결하는 어댑터다. */
@Repository
@RequiredArgsConstructor
class SearchPlaceRepositoryImpl implements SearchPlaceRepository {

  private final SearchPlaceMongoRepository mongoRepository;

  /** 활성 POI만 가져와 application 계층이 MongoDB 저장 모델을 모르게 도메인 객체로 변환한다. */
  @Override
  public List<SearchPlace> findActive() {
    return mongoRepository.findByActiveTrueOrderByPriorityDescNameAsc().stream()
        .map(SearchPlaceRepositoryImpl::toDomain)
        .toList();
  }

  private static SearchPlace toDomain(SearchPlaceDocument document) {
    return SearchPlace.builder()
        .id(document.getId())
        .type(document.getType())
        .name(document.getName())
        .aliases(document.getAliases())
        .lat(document.getLat())
        .lng(document.getLng())
        .active(document.isActive())
        .priority(document.getPriority())
        .build();
  }
}
