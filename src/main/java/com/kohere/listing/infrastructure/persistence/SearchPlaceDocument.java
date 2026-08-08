package com.kohere.listing.infrastructure.persistence;

import com.kohere.listing.domain.place.SearchPlaceType;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.MongoId;

/** MongoDB {@code searchPlaces} 컬렉션에 저장되는 키워드 검색 장소 사전 문서다. */
@Document(collection = SearchPlaceDocument.COLLECTION_NAME)
@Getter
@Builder
@AllArgsConstructor
class SearchPlaceDocument {

  static final String COLLECTION_NAME = "searchPlaces";

  /** 사람이 읽기 쉬운 고정 식별자다. seed 재실행·문서 갱신 시 같은 장소를 안정적으로 덮어쓸 수 있다. */
  @MongoId private final String id;

  /** 검색 결과의 장소 종류. 프론트 응답에서는 matchedPlace.type으로 그대로 노출된다. */
  private final SearchPlaceType type;

  /** 공식 장소명. 사용자가 볼 matchedPlace.name 값이다. */
  private final String name;

  /** 사용자가 공식 이름 대신 입력할 수 있는 별칭 목록이다. 예: "연세", "연세대", "yonsei". */
  private final Set<String> aliases;

  /** 지도 이동과 주변 매물 조회에 쓰는 대표 위도(WGS84). */
  private final double lat;

  /** 지도 이동과 주변 매물 조회에 쓰는 대표 경도(WGS84). */
  private final double lng;

  /** false면 데이터는 남기되 검색 후보에서는 제외한다. */
  private final boolean active;

  /** 같은 점수로 여러 POI가 매칭될 때 더 대표적인 장소를 먼저 고르기 위한 정렬값이다. */
  private final int priority;
}
