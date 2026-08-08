package com.kohere.listing.infrastructure.persistence;

import com.kohere.listing.domain.catalog.ListingCatalogCategory;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * MongoDB {@code listingCatalog} 컬렉션의 저장 문서다.
 *
 * <p>문서 하나가 공통 코드 하나를 설명한다. 실제 Listing 문서와 독립된 컬렉션에 두기 때문에 수백 개 매물에 같은 번역을 반복 저장하지 않는다. 복합 유니크 인덱스는
 * 같은 카테고리 안에 같은 코드가 두 번 들어가는 데이터 오류를 막는다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = ListingCatalogDocument.COLLECTION_NAME)
@CompoundIndex(
    name = "listing_catalog_category_code",
    def = "{'category': 1, 'code': 1}",
    unique = true)
class ListingCatalogDocument {

  static final String COLLECTION_NAME = "listingCatalog";

  /** 재시드해도 같은 문서를 덮어쓸 수 있도록 {@code CATEGORY:CODE} 형태의 결정적 id를 사용한다. */
  @Id private String id;

  private ListingCatalogCategory category;
  private String code;

  /** 팀의 진단 카탈로그와 같은 이름을 사용하며, 하나의 표시명에 대한 언어별 값을 보관한다. */
  private Map<String, String> label;
}
