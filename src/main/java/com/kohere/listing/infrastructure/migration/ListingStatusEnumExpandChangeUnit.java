package com.kohere.listing.infrastructure.migration;

import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import java.util.List;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * 매물 상태 enum에 {@code UPDATE_PENDING}을 더해 4종으로 넓힌다(#267).
 *
 * <p>임대인이 공개 중인 매물을 수정하면 재심사를 기다리는 이 상태로 넘어간다. {@code 0121}이 {@code validationLevel=strict} ·
 * {@code validationAction=error}로 조여 두었으므로, <b>이 유닛 없이는 새 상태의 저장 자체가 거부된다</b> — 테스트 프로파일은 {@code
 * mongock.enabled: false}라 validator가 걸리지 않아 조용히 통과하지만 dev·운영에서 {@code MongoWriteException}이 난다.
 *
 * <p><b>확장 방향이라 백필이 필요 없다.</b> 기존 문서는 전부 새 제약을 이미 만족하므로 값 이행도, 선행 정리 쿼리도 없다.
 *
 * <p>{@code 0115}·{@code 0121}이 <b>동결</b>이라 수정하지 않고, {@code 0121}의 사본에 상태 값 하나만 더한 <b>자기 사본</b>을 들고
 * {@code collMod}한다(migration-policy §8-2). {@code 0120}이 아니라 <b>{@code 0121}을 복사해야 한다</b> — 뒤에
 * 실행되는 {@code collMod}가 앞을 통째로 덮으므로, {@code 0120}을 복사하면 {@code status}가 자유 문자열로 되돌아간다.
 */
@ChangeUnit(id = "listing-status-enum-expand", order = "0122", author = "kohere")
public class ListingStatusEnumExpandChangeUnit {

  /** 살아 있는 상태 넷. 도메인 {@code Listing.ListingStatus}와 1:1로 유지한다. */
  private static final List<String> LISTING_STATUSES =
      List.of("PENDING", "PUBLISHED", "REJECTED", "UPDATE_PENDING");

  @Execution
  public void execution(MongoTemplate mongo) {
    if (!mongo.collectionExists(ListingMigrationCollections.LISTINGS)) {
      throw new IllegalStateException("listings 컬렉션이 없다 — 0115 listing-v4-baseline이 먼저 실행되어야 한다");
    }
    mongo.executeCommand(
        new Document("collMod", ListingMigrationCollections.LISTINGS)
            .append(
                "validator", new Document("$jsonSchema", listingV4WithExpandedStatusEnumSchema()))
            .append("validationLevel", "strict")
            .append("validationAction", "error"));
  }

  @RollbackExecution
  public void rollback(MongoTemplate mongo) {
    // 스키마 변경은 되돌리지 않는다 — forward-only(migration-policy §1).
  }

  /**
   * {@code 0121}의 사본에서 {@code status}의 허용 값에 {@code UPDATE_PENDING}만 더한 것이다. 이 시점에 동결된다.
   *
   * <p>{@code required}에서 빠진 셋은 값이 없을 수 있는 필드다 — {@code blogUrl}·{@code rejectionReason}·{@code
   * serviceFeedback}. 값이 없으면 키 자체를 넣지 않는다({@code null}은 타입 위반이다).
   */
  private static Document listingV4WithExpandedStatusEnumSchema() {
    return new Document("bsonType", "object")
        .append(
            "required",
            List.of(
                "_id",
                "schemaVersion",
                "landlordId",
                "contact",
                "businessRegistrationNumber",
                "ageMin",
                "ageMax",
                "title",
                "type",
                "rentalType",
                "status",
                "genderPolicy",
                "languagesSupported",
                "arcRequired",
                "favoriteCount",
                "imageUrls",
                "nearbyUniversityCodes",
                "createdAt",
                "updatedAt",
                "address",
                "building",
                "description",
                "extraNotes",
                "facilities",
                "location",
                "nearestTransit",
                "nearbyFacilities",
                "refundPolicy",
                "roomOffers",
                "preferredNationalities",
                "contractDifficulties",
                "consents"))
        .append(
            "properties",
            new Document("_id", bsonType("objectId"))
                .append("schemaVersion", new Document("enum", List.of(4)))
                .append("landlordId", bsonType("long"))
                .append("contact", contactSchema())
                .append("businessRegistrationNumber", bsonType("string"))
                .append("blogUrl", bsonType("string"))
                .append("ageMin", bsonType("int"))
                .append("ageMax", bsonType("int"))
                .append("title", localizedTextSchema())
                .append("type", bsonType("string"))
                .append("rentalType", bsonType("string"))
                .append(
                    "status", new Document("bsonType", "string").append("enum", LISTING_STATUSES))
                .append("rejectionReason", bsonType("string"))
                .append("genderPolicy", bsonType("string"))
                .append("languagesSupported", stringArray())
                .append("arcRequired", bsonType("string"))
                .append("favoriteCount", bsonType("int"))
                .append("imageUrls", stringArray())
                .append("nearbyUniversityCodes", stringArray())
                .append("createdAt", bsonType("date"))
                .append("updatedAt", bsonType("date"))
                .append("address", addressSchema())
                .append("building", buildingSchema())
                .append("description", localizedTextSchema())
                .append("extraNotes", localizedTextSchema())
                .append("facilities", facilitiesSchema())
                .append("location", bsonType("object"))
                .append("nearestTransit", nearestTransitSchema())
                .append("nearbyFacilities", stringArray())
                .append("refundPolicy", localizedTextSchema())
                .append("roomOffers", roomOffersSchema())
                .append("preferredNationalities", stringArray())
                .append("contractDifficulties", stringArray())
                .append("serviceFeedback", bsonType("string"))
                .append("consents", consentsSchema()));
  }

  /** 동의 2종과 약관 버전·동의 시각. 넷 다 필수다 — 등록 게이트가 동의를 강제하므로 값이 없을 수 없다. */
  private static Document consentsSchema() {
    return new Document("bsonType", "object")
        .append(
            "required",
            List.of("privacyPolicyAgreed", "listingExposureAgreed", "version", "agreedAt"))
        .append(
            "properties",
            new Document("privacyPolicyAgreed", bsonType("bool"))
                .append("listingExposureAgreed", bsonType("bool"))
                .append("version", bsonType("string"))
                .append("agreedAt", bsonType("date")));
  }

  /** 담당자명과 지점 대표 전화 둘뿐이다. {@code sms}는 {@code required}에서도 {@code properties}에서도 사라진다. */
  private static Document contactSchema() {
    return new Document("bsonType", "object")
        .append("required", List.of("managerName", "phone"))
        .append(
            "properties",
            new Document("managerName", bsonType("string")).append("phone", bsonType("string")));
  }

  private static Document addressSchema() {
    return new Document("bsonType", "object")
        .append("required", List.of("city", "district", "fullAddress"))
        .append(
            "properties",
            new Document("city", bsonType("string"))
                .append("district", bsonType("string"))
                .append("fullAddress", localizedTextSchema())
                .append("detail", localizedTextSchema()));
  }

  private static Document buildingSchema() {
    return new Document("bsonType", "object")
        .append(
            "required",
            List.of(
                "type",
                "usedFloorMin",
                "usedFloorMax",
                "totalFloors",
                "parkingAvailable",
                "elevatorAvailable"))
        .append(
            "properties",
            new Document("type", bsonType("string"))
                .append("usedFloorMin", bsonType("int"))
                .append("usedFloorMax", bsonType("int"))
                .append("totalFloors", bsonType("int"))
                .append("parkingAvailable", bsonType("bool"))
                .append("elevatorAvailable", bsonType("bool")));
  }

  private static Document facilitiesSchema() {
    return new Document("bsonType", "object")
        .append(
            "required",
            List.of(
                "heatingSystem",
                "kitchen",
                "laundry",
                "livingAmenities",
                "securityFeatures",
                "commonSpaces",
                "providedSupplies"))
        .append(
            "properties",
            new Document("heatingSystem", stringArray())
                .append("kitchen", stringArray())
                .append("laundry", stringArray())
                .append("livingAmenities", stringArray())
                .append("securityFeatures", stringArray())
                .append("commonSpaces", stringArray())
                .append("providedSupplies", stringArray()));
  }

  private static Document nearestTransitSchema() {
    return new Document("bsonType", "object")
        .append("required", List.of("type", "name", "walkMinutes"))
        .append(
            "properties",
            new Document("type", bsonType("string"))
                .append("name", localizedTextSchema())
                .append("walkMinutes", bsonType("int")));
  }

  private static Document roomOffersSchema() {
    Document contract =
        new Document("bsonType", "object")
            .append("required", List.of("minStayMonths", "maxStayMonths"))
            .append(
                "properties",
                new Document("minStayMonths", bsonType("int"))
                    .append("maxStayMonths", bsonType("int")));
    Document pricing =
        new Document("bsonType", "object")
            .append("required", List.of("monthlyRent", "deposit", "maintenanceFee", "currency"))
            .append(
                "properties",
                new Document("monthlyRent", bsonType("int"))
                    .append("deposit", bsonType("int"))
                    .append("maintenanceFee", bsonType("int"))
                    .append("currency", bsonType("string")));
    Document item =
        new Document("bsonType", "object")
            .append(
                "required",
                List.of(
                    "roomOfferId",
                    "name",
                    "status",
                    "contract",
                    "pricing",
                    "filterTags",
                    "roomImageUrls"))
            .append(
                "properties",
                new Document("roomOfferId", bsonType("string"))
                    .append("name", localizedTextSchema())
                    .append("status", bsonType("string"))
                    .append("contract", contract)
                    .append("pricing", pricing)
                    .append("filterTags", stringArray())
                    .append("roomImageUrls", stringArray()));
    return new Document("bsonType", "array").append("items", item);
  }

  private static Document localizedTextSchema() {
    return new Document("bsonType", "object")
        .append("required", List.of("ko", "en"))
        .append(
            "properties", new Document("ko", bsonType("string")).append("en", bsonType("string")));
  }

  private static Document stringArray() {
    return new Document("bsonType", "array").append("items", bsonType("string"));
  }

  private static Document bsonType(String type) {
    return new Document("bsonType", type);
  }
}
