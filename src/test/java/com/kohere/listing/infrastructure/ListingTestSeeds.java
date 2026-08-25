package com.kohere.listing.infrastructure;

import com.mongodb.client.model.InsertManyOptions;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.bson.Document;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * 통합 테스트가 쓰는 v4 시드다. 운영이 수동 주입하는 정본 JSON과 <b>같은 파일</b>을 읽어, 테스트와 운영이 서로 다른 데이터를 보지 않게 한다.
 *
 * <p>테스트는 Mongock을 끄고({@code mongock.enabled: false}) 인덱스 부트스트랩도 끄므로({@code
 * app.mongo.indexes-enabled: false}) 시드를 심을 주체가 없다. 이 클래스가 그 자리를 대신한다.
 *
 * <p>도메인 매퍼를 거치지 않고 드라이버 레벨로 넣는다. 정본 JSON이 실제로 저장 가능한 모양인지도 함께 확인하기 위해서다.
 */
final class ListingTestSeeds {

  /** 정본 시드의 첫 번째 매물 id다. 상세·찜·최근 본 테스트가 이 값을 기준으로 단언한다. */
  static final String LISTING_ID = "68e0000000000000000000a1";

  /** 정본 시드의 두 번째 매물 id다. */
  static final String SECOND_LISTING_ID = "68e0000000000000000000b1";

  /** {@link #LISTING_ID} 매물의 첫 번째 방 상품 id다. */
  static final String ROOM_OFFER_ID = "68e0000000000000000001a1";

  private static final String CATALOG_RESOURCE = "fixtures/listing-catalog-v4.json";
  private static final String LISTINGS_RESOURCE = "fixtures/listings-v4.json";
  private static final String UNIVERSITIES_RESOURCE = "fixtures/universities.json";

  private ListingTestSeeds() {}

  /** 번역 사전 112건을 심는다. 없으면 응답 라벨 자리에 코드값이 그대로 나가 단언이 흔들린다. */
  static void seedCatalog(MongoTemplate mongo, String collection) {
    insertAll(mongo, collection, read(CATALOG_RESOURCE));
  }

  /** 대학 좌표 원장 14건을 심는다. 없으면 등록이 인근 대학을 찾지 못해 빈 배열을 저장한다(ADR-0045). */
  static void seedUniversities(MongoTemplate mongo, String collection) {
    insertAll(mongo, collection, read(UNIVERSITIES_RESOURCE));
  }

  /** v4 매물 2건을 심는다. */
  static void seedListings(MongoTemplate mongo, String collection) {
    insertAll(mongo, collection, read(LISTINGS_RESOURCE));
  }

  /** 정본 매물 문서를 그대로 반환한다. 개별 필드를 바꿔 저장하는 테스트가 출발점으로 쓴다. */
  static List<Document> listingDocuments() {
    return read(LISTINGS_RESOURCE);
  }

  private static void insertAll(MongoTemplate mongo, String collection, List<Document> documents) {
    if (documents.isEmpty()) {
      return;
    }
    mongo
        .getCollection(collection)
        // 정본이 validator를 통과하는지도 함께 검증하므로 우회하지 않는다.
        .insertMany(documents, new InsertManyOptions().ordered(true));
  }

  private static List<Document> read(String resource) {
    try (var input = new ClassPathResource(resource).getInputStream()) {
      String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
      List<Document> documents = new ArrayList<>();
      for (BsonValue value : BsonArray.parse(json)) {
        documents.add(toDocument(value.asDocument()));
      }
      return documents;
    } catch (IOException e) {
      throw new UncheckedIOException("테스트 시드 리소스를 읽지 못했다: " + resource, e);
    }
  }

  /**
   * extended JSON({@code $oid}·{@code $date}·{@code $numberLong})을 BSON 타입 그대로 보존해 변환한다.
   *
   * <p><b>출력 모드를 EXTENDED로 못박는다.</b> 기본값인 RELAXED는 int 범위에 들어가는 {@code $numberLong}을 평범한 숫자로 써 버려,
   * 되읽을 때 {@code Integer}가 된다 — {@code landlordId}가 {@code bsonType: "long"} 검증에 걸리는 원인이었다.
   * validator가 테스트에 적용되지 않아(mongock 비활성) 지금까지 드러나지 않았다.
   */
  private static Document toDocument(BsonDocument bson) {
    return Document.parse(
        bson.toJson(JsonWriterSettings.builder().outputMode(JsonMode.EXTENDED).build()));
  }
}
