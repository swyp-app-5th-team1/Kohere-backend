package com.kohere.listing.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.model.ReplaceOptions;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/** 로컬 개발 환경에만 Numbers 기반 임시 매물을 고정 ID로 upsert한다. */
@Component
@Profile("local")
@ConditionalOnProperty(name = "app.seed.listings-enabled", havingValue = "true")
@RequiredArgsConstructor
class ListingSeedRunner implements ApplicationRunner {

  private static final String SEED_PATH = "db/seed/listings.json";

  private final MongoTemplate mongoTemplate;
  private final ObjectMapper objectMapper;

  /** seed JSON을 읽어 같은 _id 문서는 교체하고, 없는 문서는 새로 추가한다. */
  @Override
  public void run(ApplicationArguments args) throws IOException {
    JsonNode listings = objectMapper.readTree(new ClassPathResource(SEED_PATH).getInputStream());

    var collection = mongoTemplate.getCollection(ListingDocument.COLLECTION_NAME);
    for (JsonNode listingNode : listings) {
      Document listing = Document.parse(listingNode.toString());
      ObjectId listingId = listing.getObjectId("_id");
      collection.replaceOne(
          new Document("_id", listingId), listing, new ReplaceOptions().upsert(true));
    }
  }
}
