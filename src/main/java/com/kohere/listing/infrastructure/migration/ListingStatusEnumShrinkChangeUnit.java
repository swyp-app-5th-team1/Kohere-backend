package com.kohere.listing.infrastructure.migration;

import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import java.util.List;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * 매물 상태 enum을 {@code PENDING}·{@code PUBLISHED}·{@code REJECTED} 3종으로 조인다(#265).
 *
 * <p>{@code PAUSED}·{@code DELETED}는 v4 계약에 이름만 있었고 <b>전이시키는 코드가 한 줄도 없었다</b> — 등록은 {@code
 * PENDING}으로만 저장하고 조회는 {@code PUBLISHED}만 거르므로 실제로 저장된 적이 없다. 관리자 심사가 들어오면서 상태 기계를 확정하는 김에 함께 정리한다.
 *
 * <p><b>실행 전 0건을 확인한다</b> — {@code db.listings.countDocuments({status: {$in:
 * ["PAUSED","DELETED"]}})}. 남아 있으면 이 유닛이 enum을 조인 뒤 그 문서를 다시 저장할 때 validator에 걸린다(validator는 기존 문서를
 * 소급 검사하지 않으므로 조인 시점에는 조용하다).
 *
 * <p>공개된 매물을 관리자가 내리는 수단(사후 반려)은 이번 범위가 아니다. 필요해지면 {@code PAUSED}를 되살리는 새 유닛과 함께 판단한다.
 *
 * <p>{@code 0115}가 <b>동결</b>이라 수정하지 않고, {@code 0120}의 사본에 상태 enum만 얹은 <b>자기 사본</b>을 들고 {@code
 * collMod}한다(migration-policy §8-2).
 */
@ChangeUnit(id = "listing-status-enum-shrink", order = "0121", author = "kohere")
public class ListingStatusEnumShrinkChangeUnit {

  /** 살아 있는 상태 셋. 도메인 {@code Listing.ListingStatus}와 1:1로 유지한다. */
  private static final List<String> LISTING_STATUSES = List.of("PENDING", "PUBLISHED", "REJECTED");

  @Execution
  public void execution(MongoTemplate mongo) {
    if (!mongo.collectionExists(ListingMigrationCollections.LISTINGS)) {
      throw new IllegalStateException("listings 컬렉션이 없다 — 0115 listing-v4-baseline이 먼저 실행되어야 한다");
    }
    mongo.executeCommand(
        new Document("collMod", ListingMigrationCollections.LISTINGS)
            .append("validator", new Document("$jsonSchema", listingV4WithStatusEnumSchema()))
            .append("validationLevel", "strict")
            .append("validationAction", "error"));
  }

  @RollbackExecution
  public void rollback(MongoTemplate mongo) {
    // 스키마 변경은 되돌리지 않는다 — forward-only(migration-policy §1).
  }

  /**
   * {@code 0120}의 사본에서 {@code status}만 자유 문자열이 아니라 <b>열거된 셋</b>으로 조인 것이다. 이 시점에 동결된다.
   *
   * <p>{@code required}에서 빠진 셋은 값이 없을 수 있는 필드다 — {@code blogUrl}·{@code rejectionReason}·{@code
   * serviceFeedback}. 값이 없으면 키 자체를 넣지 않는다({@code null}은 타입 위반이다).
   */
  private static Document listingV4WithStatusEnumSchema() {
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
