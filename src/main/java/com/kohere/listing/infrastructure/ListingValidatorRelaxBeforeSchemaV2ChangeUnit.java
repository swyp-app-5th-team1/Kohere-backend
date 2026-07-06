package com.kohere.listing.infrastructure;

import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * listings 문서 구조 이행 전에 기존 validator를 잠시 비활성화한다.
 *
 * <p>과거 앱 기동 과정에서 v2 validator가 먼저 적용된 DB는 아직 v1 모양인 listings 문서를 수정할 때 MongoDB validation에 막힐 수
 * 있다. 실제 v2 validator는 {@link ListingValidatorV2ChangeUnit}에서 이행 이후 다시 적용한다.
 */
@ChangeUnit(id = "listing-validator-relax-before-schema-v2", order = "0099", author = "kohere")
public class ListingValidatorRelaxBeforeSchemaV2ChangeUnit {

  @Execution
  public void execution(MongoTemplate mongo) {
    if (!mongo.collectionExists(ListingDocument.COLLECTION_NAME)) {
      return;
    }

    Document command =
        new Document("collMod", ListingDocument.COLLECTION_NAME)
            .append("validator", new Document())
            .append("validationLevel", "off")
            .append("validationAction", "warn");
    mongo.executeCommand(command);
  }

  /** forward-only: validator는 후속 changeUnit에서 v2 strict로 다시 적용한다. */
  @RollbackExecution
  public void rollback() {
    // no-op
  }
}
