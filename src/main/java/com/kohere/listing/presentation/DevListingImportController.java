package com.kohere.listing.presentation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kohere.common.exception.BusinessException;
import com.kohere.common.exception.ErrorCode;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.context.annotation.Profile;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** local/dev에서 임시 매물 데이터를 직접 넣기 위한 미완성 import API다. */
@RestController
@RequestMapping("/api/dev/listings")
@Profile({"local", "dev", "test"})
@RequiredArgsConstructor
public class DevListingImportController {

  private static final String COLLECTION_NAME = "listings";

  private final MongoTemplate mongoTemplate;
  private final ObjectMapper objectMapper;

  @PostMapping(path = "/import", consumes = MediaType.APPLICATION_JSON_VALUE)
  public Map<String, Object> importListings(@RequestBody String body) {
    JsonNode root = readBody(body);
    if (!root.isArray()) {
      throw malformed("JSON 배열을 보내야 합니다.");
    }

    int savedCount = 0;
    for (JsonNode listing : root) {
      Document document = parseDocument(listing);
      Object id = document.get("_id");
      if (id == null) {
        throw malformed("_id 값이 필요합니다.");
      }
      mongoTemplate
          .getCollection(COLLECTION_NAME)
          .replaceOne(Filters.eq("_id", id), document, new ReplaceOptions().upsert(true));
      savedCount++;
    }

    return Map.of("savedCount", savedCount, "collectionName", COLLECTION_NAME);
  }

  private JsonNode readBody(String body) {
    try {
      return objectMapper.readTree(body);
    } catch (JsonProcessingException e) {
      throw malformed("JSON 본문을 해석할 수 없습니다.");
    }
  }

  private Document parseDocument(JsonNode listing) {
    if (!listing.isObject()) {
      throw malformed("배열 안에는 JSON 객체만 넣을 수 있습니다.");
    }
    try {
      return Document.parse(listing.toString());
    } catch (RuntimeException e) {
      throw malformed("MongoDB 문서 형식으로 변환할 수 없습니다.");
    }
  }

  private static ImportRequestException malformed(String message) {
    return new ImportRequestException(message);
  }

  private static final class ImportRequestException extends BusinessException {

    private ImportRequestException(String message) {
      super(ErrorCode.MALFORMED_REQUEST, message);
    }
  }
}
