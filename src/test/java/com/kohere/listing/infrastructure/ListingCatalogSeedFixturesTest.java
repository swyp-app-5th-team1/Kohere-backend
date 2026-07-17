package com.kohere.listing.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.kohere.listing.domain.ListingCatalogCategory;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** listingCatalog 정본이 중복 없이 전체 UI 카테고리를 포함하는지 검증한다. */
class ListingCatalogSeedFixturesTest {

  /** category+code 조합은 유일하고 모든 문서에 ko/en 표시명이 있어야 한다. */
  @Test
  void documents_모든항목의_식별자와_두언어라벨이_유효하다() {
    Set<String> uniqueKeys = new HashSet<>();

    for (ListingCatalogDocument document : ListingCatalogSeedFixtures.documents()) {
      String key = document.getCategory().name() + ":" + document.getCode();
      assertThat(uniqueKeys.add(key)).as("중복 카탈로그 키: %s", key).isTrue();
      assertThat(document.getId()).isEqualTo(key);
      assertThat(document.getLabel()).containsKeys("ko", "en");
      assertThat(document.getLabel().get("ko")).isNotBlank();
      assertThat(document.getLabel().get("en")).isNotBlank();
    }
  }

  /** 상세 UI에서 실제 사용하는 조건·유형·교통·시설 카테고리를 모두 제공해야 한다. */
  @Test
  void documents_매물UI의_전체공통코드카테고리를_포함한다() {
    Set<ListingCatalogCategory> categories =
        ListingCatalogSeedFixtures.documents().stream()
            .map(ListingCatalogDocument::getCategory)
            .collect(java.util.stream.Collectors.toSet());

    assertThat(categories).containsExactlyInAnyOrder(ListingCatalogCategory.values());
  }
}
