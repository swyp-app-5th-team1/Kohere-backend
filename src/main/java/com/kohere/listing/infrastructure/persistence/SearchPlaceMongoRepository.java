package com.kohere.listing.infrastructure.persistence;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

/** searchPlaces 컬렉션 접근 세부사항을 infrastructure 계층 안에 감춘다. */
interface SearchPlaceMongoRepository extends MongoRepository<SearchPlaceDocument, String> {

  /** 키워드 매칭 후보로 사용할 활성 장소를 우선순위 높은 순으로 조회한다. */
  List<SearchPlaceDocument> findByActiveTrueOrderByPriorityDescNameAsc();
}
