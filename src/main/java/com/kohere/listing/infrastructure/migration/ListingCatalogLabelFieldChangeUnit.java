package com.kohere.listing.infrastructure.migration;

import com.mongodb.client.MongoCollection;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * {@code listingCatalog.labels}를 팀의 진단 카탈로그와 같은 단수 필드 {@code label}로 이행한다.
 *
 * <p>한 문서가 코드 하나의 표시명 하나를 설명하고 그 안에 언어별 값이 들어가므로, 팀 공통 명명인 {@code label:{ko,en}}을 사용한다. 프론트 응답의
 * {@code label}은 이 하위 문서에서 현재 사용자 언어 하나를 선택한 문자열이므로 API 계약은 바뀌지 않는다.
 */
@ChangeUnit(id = "listing-catalog-label-field", order = "0111", author = "kohere")
public class ListingCatalogLabelFieldChangeUnit {

  private static final String LEGACY_FIELD = "labels";
  private static final String CURRENT_FIELD = "label";

  /** 기존 {@code labels} 필드가 남아 있는 카탈로그 문서만 찾아 {@code label}로 교체한다. */
  @Execution
  public void execution(MongoTemplate mongo) {
    if (!mongo.collectionExists(ListingMigrationCollections.LISTING_CATALOG)) {
      return;
    }

    MongoCollection<Document> catalog =
        mongo.getCollection(ListingMigrationCollections.LISTING_CATALOG);
    Document legacyFilter = new Document(LEGACY_FIELD, new Document("$exists", true));

    for (Document source : catalog.find(legacyFilter)) {
      Document migrated = migrate(source);
      catalog.replaceOne(new Document("_id", source.get("_id")), migrated);
    }
  }

  /**
   * 문서 하나의 레거시 필드명을 바꾸는 멱등 변환이다.
   *
   * <p>예외적으로 두 필드가 모두 있으면 이미 운영자가 넣은 새 {@code label}을 우선 보존하고 레거시 {@code labels}만 제거한다.
   */
  static Document migrate(Document source) {
    Document migrated = new Document(source);
    if (migrated.containsKey(CURRENT_FIELD)) {
      migrated.remove(LEGACY_FIELD);
      return migrated;
    }

    if (migrated.containsKey(LEGACY_FIELD)) {
      Object legacyLabel = migrated.remove(LEGACY_FIELD);
      migrated.put(CURRENT_FIELD, legacyLabel);
    }
    return migrated;
  }

  /** 이미 새 코드가 {@code label}을 읽으므로 과거 필드명으로 자동 복구하지 않는 forward-only 변경이다. */
  @RollbackExecution
  public void rollback() {
    // no-op
  }
}
