package com.kohere.listing.infrastructure.migration;

import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import java.util.List;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * 매물 등록·수정에서 받던 이용약관 동의({@code consents})를 저장 계약과 기존 문서 양쪽에서 걷어낸다.
 *
 * <p>임대인에게 매물마다 동의 2종을 받지 않기로 하면서 요청 DTO·도메인 VO·문서 모델·응답이 함께 사라졌다. 저장 계약에만 필드가 남으면 <b>아무도 채우지 않는 필수
 * 필드</b>가 되어 등록·수정은 물론 관리자 승인·반려 저장까지 전부 validator에 걸린다.
 *
 * <p><b>{@code required}와 {@code properties} 양쪽에서 뺀다.</b> {@code required}에서만 빼면 제약은 풀리지만 스키마가 아무도
 * 쓰지 않는 필드의 타입을 계속 주장한다. 아래 {@code $unset}으로 값 자체가 남지 않으므로 타입 정의를 남길 이유가 없다 — 그래서 {@code 0122}의
 * {@code consentsSchema()} 헬퍼는 사본에 아예 옮겨 오지 않았다.
 *
 * <p><b>{@code $unset}은 {@code collMod} 뒤에 온다.</b> {@code 0119}의 Javadoc은 {@code $unset} 배치가 먼저 와야
 * 한다고 적었지만, 그것은 필드를 <b>필수로 조일 때</b>의 확장→백필→축소 순서를 옮겨 적은 것이라 제거 방향에는 맞지 않는다. 이 컬렉션의 validator는
 * {@code validationLevel=strict}라 insert뿐 아니라 <b>update도 검사</b>한다 — {@code consents}가 아직 {@code
 * required}에 남은 상태에서 {@code $unset}을 돌리면 그 update 자체가 거부된다. 제약을 먼저 풀고, 그다음에 값을 지운다.
 *
 * <p>{@code 0115}·{@code 0122}가 <b>동결</b>이라 수정하지 않고 자기 사본을 들고 {@code collMod}한다(migration-policy
 * §8-2). 사본의 출처는 반드시 <b>{@code 0122}</b>다 — 뒤에 실행되는 {@code collMod}가 앞을 통째로 덮으므로 {@code
 * 0120}·{@code 0121}을 복사하면 {@code status} enum에서 {@code UPDATE_PENDING}이 빠져 매물 수정 저장이 전부 거부된다. 테스트
 * 프로파일은 {@code mongock.enabled: false}라 그 퇴행이 초록불 아래 숨는다.
 *
 * <p>{@code schemaVersion}은 4 그대로다. 필드 하나가 계약에서 빠질 뿐 문서의 세대가 바뀌지 않는다 — 남는 필드의 의미도, 읽는 코드도 그대로다.
 *
 * <p><b>되돌리지 않는다.</b> {@code $unset}으로 지운 동의 이력은 복구할 수 없고 {@code @RollbackExecution}은 no-op이다 —
 * forward-only(migration-policy §1).
 */
@ChangeUnit(id = "listing-consents-drop", order = "0123", author = "kohere")
public class ListingConsentsDropChangeUnit {

  /** 살아 있는 상태 넷. {@code 0122}가 정한 값이며 도메인 {@code Listing.ListingStatus}와 1:1로 유지한다. */
  private static final List<String> LISTING_STATUSES =
      List.of("PENDING", "PUBLISHED", "REJECTED", "UPDATE_PENDING");

  @Execution
  public void execution(MongoTemplate mongo) {
    if (!mongo.collectionExists(ListingMigrationCollections.LISTINGS)) {
      throw new IllegalStateException("listings 컬렉션이 없다 — 0115 listing-v4-baseline이 먼저 실행되어야 한다");
    }
    mongo.executeCommand(
        new Document("collMod", ListingMigrationCollections.LISTINGS)
            .append("validator", new Document("$jsonSchema", listingV4WithoutConsentsSchema()))
            .append("validationLevel", "strict")
            .append("validationAction", "error"));
    // 제약을 푼 뒤에야 값을 지울 수 있다(위 Javadoc). 대상이 0건이어도 no-op이라 사전 실측이 필요 없다.
    mongo
        .getCollection(ListingMigrationCollections.LISTINGS)
        .updateMany(new Document(), new Document("$unset", new Document("consents", "")));
  }

  @RollbackExecution
  public void rollback(MongoTemplate mongo) {
    // 스키마 변경은 되돌리지 않는다 — forward-only(migration-policy §1).
  }

  /**
   * {@code 0122}의 사본에서 {@code consents}를 {@code required}·{@code properties} 양쪽에서 뺀 것이다. 이 시점에
   * 동결된다.
   *
   * <p>{@code required}에서 빠진 셋은 값이 없을 수 있는 필드다 — {@code blogUrl}·{@code rejectionReason}·{@code
   * serviceFeedback}. 값이 없으면 키 자체를 넣지 않는다({@code null}은 타입 위반이다).
   */
  private static Document listingV4WithoutConsentsSchema() {
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
                "contractDifficulties"))
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
                .append("serviceFeedback", bsonType("string")));
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
