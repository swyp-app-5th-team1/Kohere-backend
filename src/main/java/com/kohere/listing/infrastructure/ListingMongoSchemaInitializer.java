package com.kohere.listing.infrastructure;

import static org.springframework.data.domain.Sort.Direction.ASC;
import static org.springframework.data.domain.Sort.Direction.DESC;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.GeoSpatialIndexType;
import org.springframework.data.mongodb.core.index.GeospatialIndex;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

/**
 * {@code listings} validator와 조회 인덱스를 기동 시 멱등 적용한다. MongoDB 변경 관리 정책에 따라 이미 존재하는 컬렉션·인덱스에도 같은 정의를
 * 안전하게 재적용한다.
 */
@Component
@RequiredArgsConstructor
class ListingMongoSchemaInitializer implements InitializingBean {

  private final MongoTemplate mongoTemplate;

  /** 애플리케이션 시작 시 listings 컬렉션 validator와 인덱스를 준비한다. */
  @Override
  public void afterPropertiesSet() {
    applyValidator();
    ensureIndexes();
  }

  /** 컬렉션이 있으면 validator를 갱신하고, 없으면 validator가 포함된 컬렉션을 생성한다. */
  private void applyValidator() {
    Document validator = new Document("$jsonSchema", listingJsonSchema());
    Document command;
    if (mongoTemplate.collectionExists(ListingDocument.COLLECTION_NAME)) {
      command =
          new Document("collMod", ListingDocument.COLLECTION_NAME)
              .append("validator", validator)
              .append("validationLevel", "strict")
              .append("validationAction", "error");
    } else {
      command =
          new Document("create", ListingDocument.COLLECTION_NAME)
              .append("validator", validator)
              .append("validationLevel", "strict")
              .append("validationAction", "error");
    }
    mongoTemplate.getDb().runCommand(command);
  }

  /** 지도 조회와 필터 조회에 필요한 MongoDB 인덱스를 멱등하게 생성한다. */
  private void ensureIndexes() {
    var indexOperations = mongoTemplate.indexOps(ListingDocument.class);

    indexOperations.createIndex(
        new GeospatialIndex("location")
            .typed(GeoSpatialIndexType.GEO_2DSPHERE)
            .named("listings_location_2dsphere"));
    indexOperations.createIndex(
        new Index()
            .on("status", ASC)
            .on("type", ASC)
            .on("roomOffers.pricing.monthlyRent", ASC)
            .named("listings_status_type_rent"));
    indexOperations.createIndex(
        new Index()
            .on("landlordId", ASC)
            .on("status", ASC)
            .on("updatedAt", DESC)
            .named("listings_landlord_status_updated"));
    indexOperations.createIndex(
        new Index()
            .on("status", ASC)
            .on("roomOffers.filterTags", ASC)
            .named("listings_status_room_filter_tags"));
    indexOperations.createIndex(
        new Index()
            .on("status", ASC)
            .on("roomOffers.inventory.availableCount", ASC)
            .named("listings_status_room_available_count"));
    indexOperations.createIndex(
        new Index()
            .on("status", ASC)
            .on("propertyPolicies.arcRequired", ASC)
            .named("listings_status_arc_required"));
  }

  /** listings 컬렉션의 최소 필수 구조를 정의하는 MongoDB JSON Schema를 만든다. */
  private static Document listingJsonSchema() {
    return new Document("bsonType", "object")
        .append(
            "required",
            List.of(
                "_id",
                "schemaVersion",
                "landlordId",
                "title",
                "type",
                "status",
                "location",
                "address",
                "propertyPolicies",
                "facilities",
                "roomOffers",
                "featureSummary",
                "imageUrls",
                "favoriteCount",
                "createdAt",
                "updatedAt"))
        .append(
            "properties",
            new Document("_id", bsonType("objectId"))
                .append("schemaVersion", integerAtLeast(1))
                .append("landlordId", bsonType(List.of("int", "long")))
                .append("title", bsonType("string"))
                .append("type", stringEnum("GOSIWON", "CO_LIVING", "SHARE_HOUSE", "OTHER"))
                .append("status", stringEnum("DRAFT", "PUBLISHED", "PAUSED", "DELETED"))
                .append("location", geoJsonPointSchema())
                .append("address", bsonType("object"))
                .append("propertyPolicies", propertyPoliciesSchema())
                .append("facilities", bsonType("object"))
                .append("roomOffers", roomOffersSchema())
                .append("featureSummary", bsonType("array"))
                .append("imageUrls", bsonType("array"))
                .append("favoriteCount", integerAtLeast(0))
                .append("createdAt", bsonType("date"))
                .append("updatedAt", bsonType("date")));
  }

  /** 위도·경도를 2dsphere 인덱스에서 사용할 GeoJSON Point 형태로 강제한다. */
  private static Document geoJsonPointSchema() {
    return new Document("bsonType", "object")
        .append("required", List.of("type", "coordinates"))
        .append(
            "properties",
            new Document("type", new Document("enum", List.of("Point")))
                .append(
                    "coordinates",
                    new Document("bsonType", "array")
                        .append("minItems", 2)
                        .append("maxItems", 2)
                        .append("items", bsonType(List.of("double", "int", "long", "decimal")))));
  }

  /** 매물 공통 정책 필드들의 필수 여부와 타입을 정의한다. */
  private static Document propertyPoliciesSchema() {
    return new Document("bsonType", "object")
        .append(
            "required",
            List.of(
                "arcRequired",
                "residentRegistrationAvailable",
                "studySuitable",
                "mealsProvided",
                "englishAvailable"))
        .append(
            "properties",
            new Document("arcRequired", bsonType("bool"))
                .append("residentRegistrationAvailable", bsonType("bool"))
                .append("studySuitable", bsonType("bool"))
                .append("mealsProvided", bsonType("bool"))
                .append("englishAvailable", bsonType("bool")));
  }

  /** 동일 조건 방 묶음인 roomOffers 배열의 필수 구조를 정의한다. */
  private static Document roomOffersSchema() {
    Document pricing =
        new Document("bsonType", "object")
            .append("required", List.of("monthlyRent", "deposit", "maintenanceFee", "currency"))
            .append(
                "properties",
                new Document("monthlyRent", integerAtLeast(0))
                    .append("deposit", integerAtLeast(0))
                    .append("maintenanceFee", integerAtLeast(0))
                    .append("currency", stringEnum("KRW")));

    Document inventory =
        new Document("bsonType", "object")
            .append("required", List.of("totalCount", "availableCount"))
            .append(
                "properties",
                new Document("totalCount", integerAtLeast(0))
                    .append("availableCount", integerAtLeast(0))
                    .append("nextAvailableFrom", bsonType(List.of("date", "null"))));

    Document roomOffer =
        new Document("bsonType", "object")
            .append(
                "required",
                List.of(
                    "roomOfferId",
                    "name",
                    "status",
                    "rentalType",
                    "pricing",
                    "contract",
                    "inventory",
                    "genderPolicy",
                    "features",
                    "filterTags",
                    "roomImageUrls"))
            .append(
                "properties",
                new Document("roomOfferId", bsonType("objectId"))
                    .append("name", bsonType("string"))
                    .append("status", stringEnum("ACTIVE", "INACTIVE"))
                    .append("rentalType", stringEnum("MONTHLY_RENT", "JEONSE"))
                    .append("pricing", pricing)
                    .append("contract", bsonType("object"))
                    .append("inventory", inventory)
                    .append("genderPolicy", bsonType("string"))
                    .append("features", bsonType("array"))
                    .append("filterTags", bsonType("array"))
                    .append("roomImageUrls", bsonType("array")));

    return new Document("bsonType", "array").append("minItems", 1).append("items", roomOffer);
  }

  /** MongoDB JSON Schema의 bsonType 선언을 간단히 만들기 위한 헬퍼다. */
  private static Document bsonType(Object type) {
    return new Document("bsonType", type);
  }

  /** 0 이상의 정수 필드 스키마를 만들기 위한 헬퍼다. */
  private static Document integerAtLeast(int minimum) {
    return bsonType(List.of("int", "long")).append("minimum", minimum);
  }

  /** 허용 가능한 문자열 enum 값 목록을 가진 스키마를 만든다. */
  private static Document stringEnum(String... values) {
    return new Document("bsonType", "string").append("enum", List.of(values));
  }
}
