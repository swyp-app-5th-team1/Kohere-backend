package com.kohere.listing.infrastructure;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * 매물 유형의 레거시 오기인 {@code GOSIWON}을 정식 값 {@code GOSHIWON}으로 이행한다.
 *
 * <p>루트 {@code type}은 매물 유형({@code ListingType})이고, {@code building.type}은 건물 유형({@code
 * BuildingType})이다. 두 enum을 함께 리네임하므로 이미 저장된 두 필드도 같은 배포에서 함께 변경해야 새 enum으로 안전하게 역직렬화할 수 있다. 값이 정확히
 * 레거시 문자열과 일치하는 문서만 갱신하며 다른 매물 필드에는 영향을 주지 않는다.
 */
@ChangeUnit(id = "listing-goshiwon-rename", order = "0106", author = "kohere")
public class ListingGoshiwonRenameChangeUnit {

  private static final String LEGACY_GOSIWON = "GOSIWON";
  private static final String GOSHIWON = "GOSHIWON";

  @Execution
  public void execution(MongoTemplate mongo) {
    MongoCollection<Document> listings = mongo.getCollection(ListingDocument.COLLECTION_NAME);

    listings.updateMany(Filters.eq("type", LEGACY_GOSIWON), Updates.set("type", GOSHIWON));
    listings.updateMany(
        Filters.eq("building.type", LEGACY_GOSIWON), Updates.set("building.type", GOSHIWON));
  }

  /** forward-only: 정식 enum 값으로 이행한 데이터는 레거시 오기로 되돌리지 않는다. */
  @RollbackExecution
  public void rollback() {
    // no-op (forward-only)
  }
}
