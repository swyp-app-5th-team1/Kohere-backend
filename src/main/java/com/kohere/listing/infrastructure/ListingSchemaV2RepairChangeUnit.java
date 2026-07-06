package com.kohere.listing.infrastructure;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * v2라고 표시됐지만 실제 저장 모양이 레거시인 listings 문서를 한 번 더 보정한다.
 *
 * <p>Mongock은 이미 성공으로 기록된 changeUnit을 다시 실행하지 않는다. 그래서 과거에 {@code schemaVersion}만 2로 찍혔거나 일부 필드만
 * 이동된 문서가 남아 있을 수 있어, 새 changeUnit id로 같은 v2 정규화를 한 번 더 적용한다.
 */
@ChangeUnit(id = "listing-schema-v2-repair", order = "0104", author = "kohere")
public class ListingSchemaV2RepairChangeUnit {

  @Execution
  public void execution(MongoTemplate mongo) {
    MongoCollection<Document> listings = mongo.getCollection(ListingDocument.COLLECTION_NAME);

    for (Document listing : listings.find(ListingSchemaV2ChangeUnit.legacyListingFilter())) {
      Document migrated = ListingSchemaV2ChangeUnit.migrate(listing);
      listings.replaceOne(
          Filters.eq("_id", listing.get("_id")), migrated, new ReplaceOptions().upsert(false));
    }
  }

  /** forward-only: 저장 스키마 보정은 되돌리지 않는다. */
  @RollbackExecution
  public void rollback() {
    // no-op
  }
}
