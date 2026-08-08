package com.kohere.listing.infrastructure.migration;

import com.kohere.listing.domain.catalog.ListingCatalogCategory;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/** {@code listingCatalog} 초기 마이그레이션이 사용하는 고정된 시드 모델. */
@Getter
@Builder
@AllArgsConstructor
final class ListingCatalogSeedDocument {

  private final String id;
  private final ListingCatalogCategory category;
  private final String code;
  private final Map<String, String> label;
}
