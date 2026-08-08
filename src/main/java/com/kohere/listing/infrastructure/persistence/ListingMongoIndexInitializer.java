package com.kohere.listing.infrastructure.persistence;

import static org.springframework.data.domain.Sort.Direction.ASC;
import static org.springframework.data.domain.Sort.Direction.DESC;

import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.GeoSpatialIndexType;
import org.springframework.data.mongodb.core.index.GeospatialIndex;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

/** {@code listings} 조회 인덱스와 MongoDB 문서 검증 규칙을 기동 시 멱등 적용한다. */
@Component
@ConditionalOnProperty(
    name = "app.mongo.indexes-enabled",
    havingValue = "true",
    matchIfMissing = true)
@RequiredArgsConstructor
public class ListingMongoIndexInitializer implements ApplicationRunner {

  private final MongoTemplate mongoTemplate;

  /** 애플리케이션 시작 시 지도·필터 조회용 MongoDB 인덱스를 준비한다. */
  @Override
  public void run(ApplicationArguments args) {
    ensureIndexes();
  }

  /**
   * MongoDB가 저장 단계에서 확인할 listings v3 JSON Schema다.
   *
   * <p>도메인 검증이 1차 방어선이고, 이 스키마는 로컬 DB나 운영 DB에 잘못된 형태의 문서가 직접 들어오는 것을 막는 2차 방어선이다.
   */
  public static Document listingJsonSchema() {
    return new Document("bsonType", "object")
        .append(
            "required",
            java.util.List.of(
                "_id",
                "schemaVersion",
                "landlordId",
                "title",
                "type",
                "status",
                "rentalType",
                "refundPolicy",
                "contract",
                "genderPolicy",
                "location",
                "address",
                "nearestTransit",
                "nearbyUniversityCodes",
                "building",
                "propertyPolicies",
                "facilities",
                "roomOffers",
                "descriptions",
                "imageUrls",
                "favoriteCount",
                "createdAt",
                "updatedAt"))
        .append(
            "properties",
            new Document()
                .append("_id", bsonType("objectId"))
                .append(
                    "schemaVersion",
                    new Document("bsonType", "int").append("enum", java.util.List.of(3)))
                .append("landlordId", bsonType("long"))
                .append("title", localizedTextSchema())
                .append("type", bsonType("string"))
                .append("status", bsonType("string"))
                .append("rentalType", bsonType("string"))
                .append("refundPolicy", refundPolicySchema())
                .append("contract", contractSchema())
                .append("genderPolicy", bsonType("string"))
                .append("location", locationSchema())
                .append("address", addressSchema())
                .append("nearestTransit", nearestTransitSchema())
                .append("nearbyUniversityCodes", stringArraySchema())
                .append("building", buildingSchema())
                .append("propertyPolicies", propertyPoliciesSchema())
                .append("facilities", facilitiesSchema())
                .append(
                    "roomOffers",
                    new Document("bsonType", "array").append("items", roomOfferSchema()))
                .append("descriptions", descriptionsSchema())
                .append("imageUrls", stringArraySchema())
                .append("favoriteCount", bsonType("int"))
                .append("createdAt", bsonType("date"))
                .append("updatedAt", bsonType("date")));
  }

  /**
   * 과거 0105 ChangeUnit이 적용했던 listings v2 JSON Schema다.
   *
   * <p>이미 배포된 마이그레이션의 의미를 바꾸지 않기 위해 v3 스키마와 별도로 보존한다. 신규 최종 validator는 {@link
   * #listingJsonSchema()}를 사용한다.
   */
  public static Document listingV2JsonSchema() {
    return new Document("bsonType", "object")
        .append(
            "required",
            java.util.List.of(
                "_id",
                "schemaVersion",
                "landlordId",
                "title",
                "type",
                "status",
                "rentalType",
                "refundPolicy",
                "contract",
                "genderPolicy",
                "location",
                "address",
                "nearestTransit",
                "nearbyUniversityCodes",
                "building",
                "propertyPolicies",
                "facilities",
                "roomOffers",
                "descriptions",
                "imageUrls",
                "favoriteCount",
                "createdAt",
                "updatedAt"))
        .append(
            "properties",
            new Document()
                .append("_id", bsonType("objectId"))
                .append("schemaVersion", bsonType("int"))
                .append("landlordId", bsonType("long"))
                .append("title", bsonType("string"))
                .append("type", bsonType("string"))
                .append("status", bsonType("string"))
                .append("rentalType", bsonType("string"))
                .append("refundPolicy", refundPolicyV2Schema())
                .append("contract", contractSchema())
                .append("genderPolicy", bsonType("string"))
                .append("location", locationSchema())
                .append("address", addressV2Schema())
                .append("nearestTransit", nearestTransitV2Schema())
                .append("nearbyUniversityCodes", stringArraySchema())
                .append("building", buildingSchema())
                .append("propertyPolicies", propertyPoliciesSchema())
                .append("facilities", facilitiesSchema())
                .append(
                    "roomOffers",
                    new Document("bsonType", "array").append("items", roomOfferV2Schema()))
                .append("descriptions", descriptionsSchema())
                .append("imageUrls", stringArraySchema())
                .append("favoriteCount", bsonType("int"))
                .append("createdAt", bsonType("date"))
                .append("updatedAt", bsonType("date")));
  }

  /** 단순 bsonType 조건을 만드는 작은 헬퍼다. */
  private static Document bsonType(String type) {
    return new Document("bsonType", type);
  }

  /** 문자열 배열 스키마다. */
  private static Document stringArraySchema() {
    return new Document("bsonType", "array").append("items", bsonType("string"));
  }

  /** 한국어와 영어가 모두 필요한 사용자 표시 문구의 공통 하위 문서 스키마다. */
  private static Document localizedTextSchema() {
    return new Document("bsonType", "object")
        .append("required", java.util.List.of("ko", "en"))
        .append(
            "properties",
            new Document().append("ko", bsonType("string")).append("en", bsonType("string")));
  }

  /** 환불 정책 하위 문서 스키마다. */
  private static Document refundPolicySchema() {
    return new Document("bsonType", "object")
        .append("required", java.util.List.of("code", "description"))
        .append(
            "properties",
            new Document()
                .append("code", bsonType("string"))
                .append("description", localizedTextSchema()));
  }

  /** v2 환불 정책은 표시 설명을 단일 문자열로 저장했다. */
  private static Document refundPolicyV2Schema() {
    return new Document("bsonType", "object")
        .append("required", java.util.List.of("code", "description"))
        .append(
            "properties",
            new Document()
                .append("code", bsonType("string"))
                .append("description", bsonType("string")));
  }

  /** 매물 공통 계약기간 하위 문서 스키마다. */
  private static Document contractSchema() {
    return new Document("bsonType", "object")
        .append("required", java.util.List.of("minStayMonths", "maxStayMonths"))
        .append(
            "properties",
            new Document()
                .append("minStayMonths", bsonType("int"))
                .append("maxStayMonths", bsonType("int")));
  }

  /** GeoJSON Point 하위 문서 스키마다. */
  private static Document locationSchema() {
    return new Document("bsonType", "object")
        .append("required", java.util.List.of("type", "coordinates"))
        .append(
            "properties",
            new Document()
                .append("type", bsonType("string"))
                .append("coordinates", new Document("bsonType", "array")));
  }

  /** 주소 하위 문서 스키마다. */
  private static Document addressSchema() {
    return new Document("bsonType", "object")
        .append("required", java.util.List.of("city", "district", "fullAddress"))
        .append(
            "properties",
            new Document()
                .append("city", bsonType("string"))
                .append("district", bsonType("string"))
                .append("fullAddress", localizedTextSchema())
                .append(
                    "detail",
                    new Document(
                        "anyOf",
                        java.util.List.of(
                            localizedTextSchema(), new Document("bsonType", "null")))));
  }

  /** v2 주소는 사용자 표시 주소를 단일 문자열로 저장했다. */
  private static Document addressV2Schema() {
    return new Document("bsonType", "object")
        .append("required", java.util.List.of("city", "district", "fullAddress"))
        .append(
            "properties",
            new Document()
                .append("city", bsonType("string"))
                .append("district", bsonType("string"))
                .append("fullAddress", bsonType("string"))
                .append("detail", new Document("bsonType", java.util.List.of("string", "null"))));
  }

  /** 가까운 교통수단 하위 문서 스키마다. */
  private static Document nearestTransitSchema() {
    return new Document("bsonType", "object")
        .append(
            "required", java.util.List.of("type", "name", "walkMinutes", "nearbyPlacesDescription"))
        .append(
            "properties",
            new Document()
                .append("type", bsonType("string"))
                .append("name", localizedTextSchema())
                .append("walkMinutes", bsonType("int"))
                .append("nearbyPlacesDescription", localizedTextSchema()));
  }

  /** v2 교통 정보는 역명과 주변 안내를 단일 문자열로 저장했다. */
  private static Document nearestTransitV2Schema() {
    return new Document("bsonType", "object")
        .append(
            "required", java.util.List.of("type", "name", "walkMinutes", "nearbyPlacesDescription"))
        .append(
            "properties",
            new Document()
                .append("type", bsonType("string"))
                .append("name", bsonType("string"))
                .append("walkMinutes", bsonType("int"))
                .append("nearbyPlacesDescription", bsonType("string")));
  }

  /** 건물 정보 하위 문서 스키마다. */
  private static Document buildingSchema() {
    return new Document("bsonType", "object")
        .append(
            "required",
            java.util.List.of(
                "type",
                "usedFloorMin",
                "usedFloorMax",
                "totalFloors",
                "parkingAvailable",
                "elevatorAvailable"))
        .append(
            "properties",
            new Document()
                .append("type", bsonType("string"))
                .append("usedFloorMin", bsonType("int"))
                .append("usedFloorMax", bsonType("int"))
                .append("totalFloors", bsonType("int"))
                .append("parkingAvailable", bsonType("bool"))
                .append("elevatorAvailable", bsonType("bool")));
  }

  /** 매물 운영 정책 하위 문서 스키마다. */
  private static Document propertyPoliciesSchema() {
    return new Document("bsonType", "object")
        .append(
            "required",
            java.util.List.of(
                "arcRequired",
                "residentRegistrationAvailable",
                "studySuitable",
                "mealsProvided",
                "englishAvailable"))
        .append(
            "properties",
            new Document()
                .append("arcRequired", bsonType("bool"))
                .append("residentRegistrationAvailable", bsonType("bool"))
                .append("studySuitable", bsonType("bool"))
                .append("mealsProvided", bsonType("bool"))
                .append("englishAvailable", bsonType("bool")));
  }

  /** 공용 시설 하위 문서 스키마다. */
  private static Document facilitiesSchema() {
    return new Document("bsonType", "object")
        .append(
            "required",
            java.util.List.of(
                "heatingSystem",
                "kitchen",
                "laundry",
                "livingAmenities",
                "securityFeatures",
                "commonSpaces",
                "providedSupplies"))
        .append(
            "properties",
            new Document()
                .append("heatingSystem", stringArraySchema())
                .append("kitchen", stringArraySchema())
                .append("laundry", stringArraySchema())
                .append("livingAmenities", stringArraySchema())
                .append("securityFeatures", stringArraySchema())
                .append(
                    "commonSpaces",
                    new Document("bsonType", "array").append("items", commonSpaceSchema()))
                .append("providedSupplies", stringArraySchema()));
  }

  /** 공용공간 하위 문서 스키마다. */
  private static Document commonSpaceSchema() {
    return new Document("bsonType", "object")
        .append("required", java.util.List.of("type"))
        .append(
            "properties",
            new Document()
                .append("type", bsonType("string"))
                .append("count", new Document("bsonType", java.util.List.of("int", "null"))));
  }

  /** 방 상품 하위 문서 스키마다. */
  private static Document roomOfferSchema() {
    return new Document("bsonType", "object")
        .append(
            "required",
            java.util.List.of(
                "roomOfferId",
                "name",
                "status",
                "pricing",
                "inventory",
                "filterTags",
                "roomImageUrls"))
        .append(
            "properties",
            new Document()
                .append("roomOfferId", bsonType("string"))
                .append("name", localizedTextSchema())
                .append("status", bsonType("string"))
                .append("pricing", pricingSchema())
                .append("inventory", inventorySchema())
                .append("filterTags", stringArraySchema())
                .append("roomImageUrls", stringArraySchema()));
  }

  /** v2 방 상품은 사용자 표시 방 이름을 단일 문자열로 저장했다. */
  private static Document roomOfferV2Schema() {
    return new Document("bsonType", "object")
        .append(
            "required",
            java.util.List.of(
                "roomOfferId",
                "name",
                "status",
                "pricing",
                "inventory",
                "filterTags",
                "roomImageUrls"))
        .append(
            "properties",
            new Document()
                .append("roomOfferId", bsonType("string"))
                .append("name", bsonType("string"))
                .append("status", bsonType("string"))
                .append("pricing", pricingSchema())
                .append("inventory", inventorySchema())
                .append("filterTags", stringArraySchema())
                .append("roomImageUrls", stringArraySchema()));
  }

  /** 방 상품 가격 하위 문서 스키마다. */
  private static Document pricingSchema() {
    return new Document("bsonType", "object")
        .append(
            "required", java.util.List.of("monthlyRent", "deposit", "maintenanceFee", "currency"))
        .append(
            "properties",
            new Document()
                .append("monthlyRent", bsonType("int"))
                .append("deposit", bsonType("int"))
                .append("maintenanceFee", bsonType("int"))
                .append("currency", bsonType("string")));
  }

  /** 방 재고 하위 문서 스키마다. */
  private static Document inventorySchema() {
    return new Document("bsonType", "object")
        .append("required", java.util.List.of("totalCount", "availableCount"))
        .append(
            "properties",
            new Document()
                .append("totalCount", bsonType("int"))
                .append("availableCount", bsonType("int"))
                .append(
                    "nextAvailableFrom",
                    new Document("bsonType", java.util.List.of("date", "null"))));
  }

  /** 다국어 설명 하위 문서 스키마다. */
  private static Document descriptionsSchema() {
    return new Document("bsonType", "object")
        .append("required", java.util.List.of("ko", "en", "extraNotes"))
        .append(
            "properties",
            new Document()
                .append("ko", bsonType("string"))
                .append("en", bsonType("string"))
                .append("extraNotes", bsonType("string")));
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

    var catalogIndexOperations = mongoTemplate.indexOps(ListingCatalogDocument.class);

    // category+code는 프론트에 전달할 하나의 번역 항목을 식별한다. 같은 코드가 카테고리별로 다른 라벨을 가질 수 있다.
    catalogIndexOperations.createIndex(
        new Index()
            .on("category", ASC)
            .on("code", ASC)
            .unique()
            .named("listing_catalog_category_code"));

    var searchPlaceIndexOperations = mongoTemplate.indexOps(SearchPlaceDocument.class);

    // 키워드 검색은 MVP에서 활성 POI를 우선순위 높은 순으로 읽어 애플리케이션에서 점수화한다.
    searchPlaceIndexOperations.createIndex(
        new Index()
            .on("active", ASC)
            .on("priority", DESC)
            .on("name", ASC)
            .named("search_places_active_priority_name"));

    var favoriteIndexOperations = mongoTemplate.indexOps(FavoriteDocument.class);

    // 사용자 한 명이 같은 매물을 여러 번 찜할 수 없게 DB 레벨에서 보장한다.
    // 서비스는 중복 키 예외를 "이미 찜한 상태"로 바꿔 멱등한 200 OK 응답을 만든다.
    favoriteIndexOperations.createIndex(
        new Index()
            .on("userId", ASC)
            .on("listingId", ASC)
            .unique()
            .named("favorites_user_listing"));

    // 내 찜 목록은 MVP에서 favoritedAt desc 고정이므로 사용자+찜시각 인덱스로 최신순 페이지 조회를 지원한다.
    favoriteIndexOperations.createIndex(
        new Index().on("userId", ASC).on("favoritedAt", DESC).named("favorites_user_favoritedAt"));

    var recentListingIndexOperations = mongoTemplate.indexOps(RecentListingDocument.class);

    // 같은 사용자가 같은 매물을 여러 번 봐도 최근 본 목록에는 한 번만 남도록 DB 레벨에서 보장한다.
    recentListingIndexOperations.createIndex(
        new Index()
            .on("userId", ASC)
            .on("listingId", ASC)
            .unique()
            .named("recentListings_user_listing"));

    // 최근 본 목록 조회와 사용자별 30개 보관 정리는 모두 viewedAt desc 순서를 사용한다.
    recentListingIndexOperations.createIndex(
        new Index().on("userId", ASC).on("viewedAt", DESC).named("recentListings_user_viewedAt"));
  }
}
