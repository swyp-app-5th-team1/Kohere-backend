package com.kohere.listing.infrastructure;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.ReplaceOptions;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Listing UI 공통 코드의 한국어·영어 번역을 {@code listingCatalog} 컬렉션에 멱등 적재한다.
 *
 * <p>각 문서는 결정적 {@code _id=CATEGORY:CODE}를 가지므로 이미 문서가 있어도 같은 키를 교체 저장할 수 있다. 매물 문서와 분리된 카탈로그이기 때문에
 * 매물 수와 관계없이 번역은 코드당 한 번만 저장한다.
 */
@ChangeUnit(id = "listing-catalog-seed", order = "0108", author = "kohere")
public class ListingCatalogSeedChangeUnit {

  /** 전체 정본을 upsert하여 신규 환경과 기존 환경에 같은 카탈로그를 만든다. */
  @Execution
  public void execution(MongoTemplate mongo) {
    MongoCollection<Document> collection =
        mongo.getCollection(ListingCatalogDocument.COLLECTION_NAME);

    for (ListingCatalogDocument source : ListingCatalogSeedFixtures.documents()) {
      Document document =
          new Document("_id", source.getId())
              .append("category", source.getCategory().name())
              .append("code", source.getCode())
              // 0108의 기존 출력 계약은 유지한다. 후속 0111이 팀 공통 필드명인 label로 이행한다.
              .append("labels", new Document(source.getLabel()));
      collection.replaceOne(
          new Document("_id", source.getId()), document, new ReplaceOptions().upsert(true));
    }
  }

  /** 카탈로그는 다른 데이터가 참조할 수 있으므로 운영 롤백에서 자동 삭제하지 않는 forward-only 변경이다. */
  @RollbackExecution
  public void rollback() {
    // no-op
  }
}
