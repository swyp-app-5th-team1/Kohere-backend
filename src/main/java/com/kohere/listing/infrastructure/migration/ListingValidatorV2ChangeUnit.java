package com.kohere.listing.infrastructure.migration;

import com.kohere.listing.infrastructure.persistence.ListingMongoIndexInitializer;
import com.mongodb.client.model.CreateCollectionOptions;
import com.mongodb.client.model.ValidationAction;
import com.mongodb.client.model.ValidationLevel;
import com.mongodb.client.model.ValidationOptions;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

/** listings 컬렉션의 MongoDB validator를 v2 저장 스키마에 맞춘다. */
@ChangeUnit(id = "listing-validator-v2", order = "0105", author = "kohere")
public class ListingValidatorV2ChangeUnit {

  @Execution
  public void execution(MongoTemplate mongo) {
    Document validator =
        new Document("$jsonSchema", ListingMongoIndexInitializer.listingV2JsonSchema());

    if (!mongo.collectionExists(ListingMigrationCollections.LISTINGS)) {
      mongo
          .getDb()
          .createCollection(
              ListingMigrationCollections.LISTINGS,
              new CreateCollectionOptions()
                  .validationOptions(
                      new ValidationOptions()
                          .validator(validator)
                          .validationLevel(ValidationLevel.STRICT)
                          .validationAction(ValidationAction.ERROR)));
      return;
    }

    Document command =
        new Document("collMod", ListingMigrationCollections.LISTINGS)
            .append("validator", validator)
            .append("validationLevel", "strict")
            .append("validationAction", "error");
    mongo.executeCommand(command);
  }

  /** forward-only: v2 validator 적용은 되돌리지 않는다. */
  @RollbackExecution
  public void rollback() {
    // no-op
  }
}
