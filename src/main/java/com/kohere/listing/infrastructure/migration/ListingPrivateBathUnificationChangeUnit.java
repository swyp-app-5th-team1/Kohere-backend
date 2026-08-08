package com.kohere.listing.infrastructure.migration;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import java.util.List;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * 매물의 레거시 개인 화장실 조건을 개인 욕실 조건으로 통합한다.
 *
 * <p>listing enum에서 {@code PRIVATE_TOILET}을 제거하므로, 기존 매물 문서의 {@code featureSummary}, {@code
 * roomOffers.filterTags}, {@code roomOffers.features}에 남은 레거시 문자열을 {@code PRIVATE_BATH}로 이행한다.
 */
@ChangeUnit(id = "listing-private-bath-unification", order = "0101", author = "kohere")
public class ListingPrivateBathUnificationChangeUnit {

  private static final String LISTINGS = "listings";
  private static final String LEGACY_PRIVATE_TOILET = "PRIVATE_TOILET";
  private static final String PRIVATE_BATH = "PRIVATE_BATH";

  @Execution
  public void execution(MongoTemplate mongo) {
    MongoCollection<Document> listings = mongo.getCollection(LISTINGS);

    listings.updateMany(
        Filters.eq("featureSummary", LEGACY_PRIVATE_TOILET),
        Updates.addToSet("featureSummary", PRIVATE_BATH));
    listings.updateMany(
        Filters.eq("featureSummary", LEGACY_PRIVATE_TOILET),
        Updates.pull("featureSummary", LEGACY_PRIVATE_TOILET));

    unifyRoomOfferArray(listings, "filterTags");
    unifyRoomOfferArray(listings, "features");
  }

  private static void unifyRoomOfferArray(MongoCollection<Document> listings, String field) {
    String path = "roomOffers." + field;
    String filteredPath = "roomOffers.$[room]." + field;
    UpdateOptions options =
        new UpdateOptions()
            .arrayFilters(List.of(Filters.eq("room." + field, LEGACY_PRIVATE_TOILET)));

    listings.updateMany(
        Filters.eq(path, LEGACY_PRIVATE_TOILET),
        Updates.addToSet(filteredPath, PRIVATE_BATH),
        options);
    listings.updateMany(
        Filters.eq(path, LEGACY_PRIVATE_TOILET),
        Updates.pull(filteredPath, LEGACY_PRIVATE_TOILET),
        options);
  }

  /** forward-only: enum 통합 이행은 되돌리지 않는다. */
  @RollbackExecution
  public void rollback() {
    // no-op (forward-only)
  }
}
