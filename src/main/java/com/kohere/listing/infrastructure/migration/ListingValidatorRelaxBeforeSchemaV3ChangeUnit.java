package com.kohere.listing.infrastructure.migration;

import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * listings v2 문서를 v3 다국어 구조로 바꾸기 전에 기존 MongoDB validator를 잠시 해제한다.
 *
 * <p>v2 validator는 {@code title}, {@code address.fullAddress} 등을 문자열로 요구한다. v3 마이그레이션이 이 값을 {@code
 * {ko, en}} 문서로 교체하는 동안 기존 규칙이 켜져 있으면 정상적인 변경도 거부되므로, 이 ChangeUnit이 먼저 검증을 해제하고 {@link
 * ListingValidatorV3ChangeUnit}이 변환 완료 후 새 규칙을 다시 적용한다.
 */
@ChangeUnit(id = "listing-validator-relax-before-schema-v3", order = "0107", author = "kohere")
public class ListingValidatorRelaxBeforeSchemaV3ChangeUnit {

  /** listings 컬렉션이 있는 환경에서만 validator를 비활성화한다. */
  @Execution
  public void execution(MongoTemplate mongo) {
    if (!mongo.collectionExists(ListingMigrationCollections.LISTINGS)) {
      return;
    }

    Document command =
        new Document("collMod", ListingMigrationCollections.LISTINGS)
            .append("validator", new Document())
            .append("validationLevel", "off")
            .append("validationAction", "warn");
    mongo.executeCommand(command);
  }

  /** forward-only: 후속 ChangeUnit이 v3 strict validator를 적용하므로 별도 롤백은 하지 않는다. */
  @RollbackExecution
  public void rollback() {
    // no-op
  }
}
