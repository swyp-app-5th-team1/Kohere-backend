package com.kohere.listing.infrastructure;

import com.mongodb.client.model.CreateCollectionOptions;
import com.mongodb.client.model.ValidationAction;
import com.mongodb.client.model.ValidationLevel;
import com.mongodb.client.model.ValidationOptions;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

/** listings 다국어 v3 이행이 끝난 뒤 MongoDB strict validator를 적용한다. */
@ChangeUnit(id = "listing-validator-v3", order = "0110", author = "kohere")
public class ListingValidatorV3ChangeUnit {

  /** 새로 생성되는 컬렉션과 기존 컬렉션 모두 같은 v3 검증 규칙을 사용하게 한다. */
  @Execution
  public void execution(MongoTemplate mongo) {
    Document validator =
        new Document("$jsonSchema", ListingMongoIndexInitializer.listingJsonSchema());
    if (!mongo.collectionExists(ListingDocument.COLLECTION_NAME)) {
      mongo
          .getDb()
          .createCollection(
              ListingDocument.COLLECTION_NAME,
              new CreateCollectionOptions()
                  .validationOptions(
                      new ValidationOptions()
                          .validator(validator)
                          .validationLevel(ValidationLevel.STRICT)
                          .validationAction(ValidationAction.ERROR)));
      return;
    }

    Document command =
        new Document("collMod", ListingDocument.COLLECTION_NAME)
            .append("validator", validator)
            .append("validationLevel", "strict")
            .append("validationAction", "error");
    mongo.executeCommand(command);
  }

  /** forward-only: v3 저장 계약을 롤백으로 느슨하게 만들지 않는다. */
  @RollbackExecution
  public void rollback() {
    // no-op
  }
}
