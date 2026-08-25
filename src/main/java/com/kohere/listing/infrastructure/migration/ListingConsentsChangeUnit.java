package com.kohere.listing.infrastructure.migration;

import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import java.util.List;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * 매물 이용약관 동의({@code consents})를 저장 계약에 넣는다(#265).
 *
 * <p>등록 시 개인정보 수집·이용 동의와 매물 정보 제공 및 노출 동의를 받는다. 등록 게이트가 둘 다 {@code true}를 강제하므로 <b>저장된 매물은 예외 없이
 * 동의를 마친 매물</b>이고, 심사 단계가 동의 여부를 판단 기준으로 다시 쓰지 않는다. 함께 저장하는 {@code version}·{@code agreedAt}이 실제
 * 증빙이다 — 동의 사실의 입증 책임이 사업자에게 있어 "코드가 막는다"는 주장만으로는 부족하다.
 *
 * <p>{@code version}은 회원 약관 버전({@code users.terms_version})과 <b>별개 값</b>이다. 그쪽은 계정 단위로 가입 시 1회
 * 기록되지만 매물 동의는 매물마다 등록 시점이라, 같은 임대인의 매물이 서로 다른 버전을 가질 수 있다.
 *
 * <p><b>이행 대상 문서가 있으면 이 유닛 앞에 {@code $set} 백필이 와야 한다</b>(migration-policy §4). validator는 기존 문서를 소급
 * 검사하지 않으므로 당장은 조용하지만, 신규 insert는 검사하므로 시드 재주입 시점에 드러난다. 착수 시점에 {@code
 * db.listings.countDocuments({})}로 0건임을 확인했다면 백필 배치가 필요 없다.
 *
 * <p>{@code 0115}가 <b>동결</b>이라 수정하지 않고, {@code 0116}·{@code 0119}의 선례대로 이 유닛도 <b>자기 스키마 사본</b>을 들고
 * {@code collMod}로 갈아 끼운다(migration-policy §8-2). 나중에 실행되는 {@code collMod}가 앞의 것을 덮으므로 앞선 유닛들의 사본에
 * {@code consents}가 없어도 최종 상태는 이 유닛의 사본이다.
 */
@ChangeUnit(id = "listing-consents", order = "0120", author = "kohere")
public class ListingConsentsChangeUnit {

  @Execution
  public void execution(MongoTemplate mongo) {
    if (!mongo.collectionExists(ListingMigrationCollections.LISTINGS)) {
      // 0115가 먼저 돌아 컬렉션을 만들므로 여기 도달하지 않는다. 순서가 흔들린 환경에서 조용히 통과하지 않도록 막는다.
      throw new IllegalStateException("listings 컬렉션이 없다 — 0115 listing-v4-baseline이 먼저 실행되어야 한다");
    }
    mongo.executeCommand(
        new Document("collMod", ListingMigrationCollections.LISTINGS)
            .append("validator", new Document("$jsonSchema", listingV4WithConsentsSchema()))
            .append("validationLevel", "strict")
            .append("validationAction", "error"));
  }

  @RollbackExecution
  public void rollback(MongoTemplate mongo) {
    // 스키마 변경은 되돌리지 않는다 — forward-only(migration-policy §1). 잘못됐다면 새 유닛으로 고친다.
  }

  /**
   * {@code 0119}의 v4 스키마에 {@code consents}를 더한 사본이다. 이 시점에 동결되며 이후 개정은 또 다른 changeUnit이 자기 사본을 들고
   * 온다.
   *
   * <p>{@code required}에서 빠진 셋은 값이 없을 수 있는 필드다 — {@code blogUrl}(선택 입력), {@code rejectionReason}(반려
   * 시에만), {@code serviceFeedback}(선택 설문). <b>이 셋은 값이 없으면 키 자체를 넣지 않는다</b> — {@code bsonType}이 선언돼
   * 있어 {@code null}을 넣으면 타입 위반으로 insert가 거부된다.
   */
  private static Document listingV4WithConsentsSchema() {
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
                .append("status", bsonType("string"))
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
