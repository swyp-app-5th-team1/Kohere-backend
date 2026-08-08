package com.kohere.listing.infrastructure.migration;

import com.kohere.listing.domain.place.SearchPlaceType;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.MongoId;

/** {@code searchPlaces} 초기 마이그레이션이 사용하는 고정된 시드 모델. */
@Document(collection = ListingMigrationCollections.SEARCH_PLACES)
@Getter
@Builder
@AllArgsConstructor
final class SearchPlaceSeedDocument {

  @MongoId private final String id;
  private final SearchPlaceType type;
  private final String name;
  private final Set<String> aliases;
  private final double lat;
  private final double lng;
  private final boolean active;
  private final int priority;
}
