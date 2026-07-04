package com.kohere.listing.infrastructure;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

/** UI 필터명과 맞지 않던 기존 listing 조건 태그 문자열을 새 코드로 이행한다. */
@ChangeUnit(id = "listing-condition-tag-rename", order = "0102", author = "kohere")
public class ListingConditionTagRenameChangeUnit {

  private static final String LISTINGS = "listings";
  private static final Map<String, String> RENAMES =
      Map.of(
          "IMMEDIATE_MOVE_IN", "MOVE_IN_NOW",
          "ENGLISH_AVAILABLE", "ENGLISH_OK",
          "RESIDENT_REGISTRATION", "ADDRESS_REGISTRATION",
          "NO_MAINTENANCE_FEE", "NO_MAINT_FEE",
          "MEALS_PROVIDED", "MEALS_INCLUDED");

  @Execution
  public void execution(MongoTemplate mongo) {
    MongoCollection<Document> listings = mongo.getCollection(LISTINGS);
    RENAMES.forEach(
        (legacy, replacement) -> {
          renameArrayValue(listings, "featureSummary", legacy, replacement);
          renameRoomOfferFilterTag(listings, legacy, replacement);
        });
  }

  private static void renameArrayValue(
      MongoCollection<Document> listings, String field, String legacy, String replacement) {
    listings.updateMany(Filters.eq(field, legacy), Updates.addToSet(field, replacement));
    listings.updateMany(Filters.eq(field, legacy), Updates.pull(field, legacy));
  }

  private static void renameRoomOfferFilterTag(
      MongoCollection<Document> listings, String legacy, String replacement) {
    UpdateOptions options =
        new UpdateOptions().arrayFilters(List.of(Filters.eq("room.filterTags", legacy)));
    listings.updateMany(
        Filters.eq("roomOffers.filterTags", legacy),
        Updates.addToSet("roomOffers.$[room].filterTags", replacement),
        options);
    listings.updateMany(
        Filters.eq("roomOffers.filterTags", legacy),
        Updates.pull("roomOffers.$[room].filterTags", legacy),
        options);
  }

  /** forward-only: enum 문자열 이행은 되돌리지 않는다. */
  @RollbackExecution
  public void rollback() {
    // no-op (forward-only)
  }
}
