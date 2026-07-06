package com.kohere.listing.infrastructure;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * listings 컬렉션의 v1 문서를 v2 저장 스키마로 이행한다.
 *
 * <p>v2의 핵심 변경은 "매물 전체에 공통인 값은 Listing 루트로 올리고, 방마다 다른 값만 roomOffers[]에 남기는 것"이다. 예를 들어 임대 방식,
 * 계약기간, 환불 정책, 성별 정책은 모든 방에 반복 저장하지 않고 루트에 저장한다. 반대로 가격, 재고, 검색 태그는 방 상품마다 다를 수 있으므로 roomOffers[]에
 * 남긴다.
 *
 * <p>API 응답은 application 계층의 {@link com.kohere.listing.application.ListingService}와 매퍼가 기존 프론트 계약에
 * 맞춰 다시 조립한다. 이 ChangeUnit은 MongoDB 안의 저장 모양만 안전하게 바꾸는 역할을 한다.
 */
@ChangeUnit(id = "listing-schema-v2", order = "0103", author = "kohere")
public class ListingSchemaV2ChangeUnit {

  private static final String LISTINGS = "listings";
  private static final String DEFAULT_RENTAL_TYPE = "MONTHLY_RENT";
  private static final String DEFAULT_GENDER_POLICY = "ANY";
  private static final String DEFAULT_REFUND_POLICY_CODE = "FULL_REFUND_BEFORE_7_DAYS";
  private static final String DEFAULT_REFUND_POLICY_DESCRIPTION = "입주 7일 전 취소 시 전액 환불";

  /**
   * v2가 아닌 listings 문서를 찾아 같은 _id로 교체 저장한다.
   *
   * <p>MongoDB의 update 연산으로도 일부 필드는 옮길 수 있지만, 이번 변경은 중첩 위치 이동과 roomOffers[] 내부 필드 제거가 함께 일어난다. 문서를
   * 한 번 읽어 새 모양으로 만든 뒤 replaceOne으로 교체하면 각 변환 단계를 코드로 읽기 쉽고, 부분 업데이트 순서 때문에 생기는 중간 상태도 줄일 수 있다.
   */
  @Execution
  public void execution(MongoTemplate mongo) {
    MongoCollection<Document> listings = mongo.getCollection(LISTINGS);

    for (Document listing : listings.find(legacyListingFilter())) {
      Document migrated = migrate(listing);
      listings.replaceOne(
          Filters.eq("_id", listing.get("_id")), migrated, new ReplaceOptions().upsert(false));
    }
  }

  /** v2로 정규화해야 하는 레거시 문서 조건이다. */
  static Bson legacyListingFilter() {
    return Filters.or(
        Filters.ne("schemaVersion", 2),
        Filters.exists("rentalType", false),
        Filters.exists("refundPolicy", false),
        Filters.exists("contract", false),
        Filters.exists("genderPolicy", false),
        Filters.exists("nearestTransit.nearbyPlacesDescription", false),
        Filters.exists("facilities.heatingSystem", false),
        Filters.exists("facilities.kitchen", false),
        Filters.exists("descriptions.extraNotes", false),
        Filters.exists("nearbyPlacesDescription"),
        Filters.exists("featureSummary"),
        Filters.exists("extraNotes"),
        Filters.exists("building.heatingSystem"),
        Filters.exists("roomOffers.rentalType"),
        Filters.exists("roomOffers.contract"),
        Filters.exists("roomOffers.genderPolicy"),
        Filters.exists("roomOffers.features"));
  }

  /** listings 문서 하나를 v2 저장 구조로 정규화한다. */
  static Document migrate(Document listing) {
    Document migrated = new Document(listing);
    migrateScalarFields(migrated);
    migrateRootPolicyFields(migrated);
    migrateLocationFields(migrated);
    migrateBuildingAndFacilities(migrated);
    migrateDescriptions(migrated);
    migrateRoomOffers(migrated);
    migrated.remove("featureSummary");
    migrated.put("schemaVersion", 2);
    return migrated;
  }

  /**
   * v2 validator가 기대하는 루트 숫자 타입으로 정규화한다.
   *
   * <p>기존 수동 적재 데이터에는 {@code landlordId: 1}처럼 int32로 저장된 값이 있을 수 있다. 도메인 모델은 Long으로 다루므로, 마이그레이션 중
   * int32/long 차이를 정리해서 새 validator를 통과하게 만든다.
   */
  private static void migrateScalarFields(Document listing) {
    Object landlordId = listing.get("landlordId");
    if (landlordId instanceof Number number) {
      listing.put("landlordId", number.longValue());
    }
  }

  /**
   * v1에서 roomOffers[]에 반복 저장되던 공통 정책 값을 Listing 루트로 올린다.
   *
   * <p>기존 문서에 방 상품이 여러 개 있으면 첫 번째 방 상품의 값을 대표값으로 사용한다. 사용자가 승인한 새 스키마는 이 값들이 매물 공통이라는 전제를 갖기 때문에,
   * 서로 다른 방마다 다른 계약기간이나 성별 정책이 저장된 과거 문서는 사람이 따로 정리해야 한다.
   */
  private static void migrateRootPolicyFields(Document listing) {
    Document firstRoomOffer = firstRoomOffer(listing);
    Document firstRoomContract = nestedDocument(firstRoomOffer, "contract");

    listing.putIfAbsent(
        "rentalType", textOrDefault(firstRoomOffer.getString("rentalType"), DEFAULT_RENTAL_TYPE));
    listing.putIfAbsent(
        "genderPolicy",
        textOrDefault(firstRoomOffer.getString("genderPolicy"), DEFAULT_GENDER_POLICY));
    listing.putIfAbsent(
        "contract",
        new Document("minStayMonths", intOrDefault(firstRoomContract, "minStayMonths", 1))
            .append(
                "maxStayMonths",
                intOrDefault(
                    firstRoomContract,
                    "maxStayMonths",
                    intOrDefault(firstRoomContract, "minStayMonths", 1))));
    listing.putIfAbsent("refundPolicy", refundPolicyFrom(firstRoomContract));
  }

  /**
   * 주변 편의시설 자유 문구를 nearestTransit 하위로 이동한다.
   *
   * <p>프론트 응답 키는 계속 {@code locationInfo.nearbyPlacesDescription}이지만, DB에서는 교통/위치 주변 정보와 함께 관리하기 위해
   * {@code nearestTransit.nearbyPlacesDescription}에 보관한다.
   */
  private static void migrateLocationFields(Document listing) {
    Document nearestTransit = nestedDocument(listing, "nearestTransit");
    String nearbyPlacesDescription =
        textOrDefault(
            nearestTransit.getString("nearbyPlacesDescription"),
            textOrDefault(listing.getString("nearbyPlacesDescription"), ""));
    nearestTransit.put("nearbyPlacesDescription", nearbyPlacesDescription);
    listing.put("nearestTransit", nearestTransit);
    listing.remove("nearbyPlacesDescription");
  }

  /**
   * 건물 하위의 단일 난방 값을 facilities.heatingSystem[]으로 이동하고, 새 kitchen 배열을 보강한다.
   *
   * <p>기존 API 응답의 {@code propertyInfo.building.heatingSystem}은 매퍼에서 대표 난방값으로 되살린다. 저장소에서는
   * facilities 안에 여러 난방 방식을 담을 수 있도록 배열로 둔다.
   */
  private static void migrateBuildingAndFacilities(Document listing) {
    Document building = nestedDocument(listing, "building");
    Document facilities = nestedDocument(listing, "facilities");

    Object legacyHeatingSystem = building.remove("heatingSystem");
    if (!facilities.containsKey("heatingSystem")) {
      facilities.put(
          "heatingSystem",
          legacyHeatingSystem == null ? List.of() : List.of(legacyHeatingSystem.toString()));
    }
    facilities.putIfAbsent("kitchen", List.of());

    listing.put("building", building);
    listing.put("facilities", facilities);
  }

  /**
   * 자유 입력 주의사항을 descriptions 하위로 이동한다.
   *
   * <p>기존 응답의 {@code content.extraNotes}는 계속 유지하지만, 저장 스키마에서는 상세 설명 묶음에 같이 보관한다.
   */
  private static void migrateDescriptions(Document listing) {
    Document descriptions = nestedDocument(listing, "descriptions");
    descriptions.put(
        "extraNotes",
        textOrDefault(
            descriptions.getString("extraNotes"),
            textOrDefault(listing.getString("extraNotes"), "")));
    listing.put("descriptions", descriptions);
    listing.remove("extraNotes");
  }

  /**
   * 방 상품에서 더 이상 저장하지 않는 반복 필드를 제거한다.
   *
   * <p>roomOfferId는 사용자가 보낸 새 샘플처럼 문자열 ObjectId hex로 보관한다. 과거 문서에 ObjectId 타입으로 저장된 값도 같은 24자리 문자열로
   * 정규화한다.
   */
  private static void migrateRoomOffers(Document listing) {
    List<Document> roomOffers = roomOffers(listing);
    roomOffers.forEach(
        roomOffer -> {
          roomOffer.put("roomOfferId", roomOfferId(roomOffer.get("roomOfferId")));
          roomOffer.remove("rentalType");
          roomOffer.remove("contract");
          roomOffer.remove("genderPolicy");
          roomOffer.remove("features");
          roomOffer.putIfAbsent("filterTags", List.of());
          roomOffer.putIfAbsent("roomImageUrls", List.of());
        });
    listing.put("roomOffers", roomOffers);
  }

  /** roomOffers[]의 첫 항목을 반환한다. 없으면 기본값 생성용 빈 문서를 반환한다. */
  private static Document firstRoomOffer(Document listing) {
    List<Document> roomOffers = roomOffers(listing);
    return roomOffers.isEmpty() ? new Document() : roomOffers.getFirst();
  }

  /** listings 문서에서 roomOffers[]를 Document 리스트로 안전하게 꺼낸다. */
  private static List<Document> roomOffers(Document listing) {
    Object value = listing.get("roomOffers");
    if (!(value instanceof List<?> values)) {
      return new ArrayList<>();
    }
    List<Document> roomOffers = new ArrayList<>();
    for (Object item : values) {
      if (item instanceof Document document) {
        roomOffers.add(new Document(document));
      }
    }
    return roomOffers;
  }

  /** 중첩 문서를 안전하게 가져온다. 없거나 타입이 다르면 빈 문서를 반환한다. */
  private static Document nestedDocument(Document document, String key) {
    Object value = document.get(key);
    return value instanceof Document nested ? new Document(nested) : new Document();
  }

  /** contract 하위의 환불 정책을 루트 refundPolicy로 옮기되, 없으면 기본 정책을 넣는다. */
  private static Document refundPolicyFrom(Document contract) {
    Document refundPolicy = nestedDocument(contract, "refundPolicy");
    refundPolicy.putIfAbsent("code", DEFAULT_REFUND_POLICY_CODE);
    refundPolicy.putIfAbsent("description", DEFAULT_REFUND_POLICY_DESCRIPTION);
    return refundPolicy;
  }

  /** MongoDB 숫자 타입 차이를 신경 쓰지 않고 int 값을 읽는다. */
  private static int intOrDefault(Document document, String key, int defaultValue) {
    Object value = document.get(key);
    return value instanceof Number number ? number.intValue() : defaultValue;
  }

  /** null 또는 빈 문자열이면 기본 문구를 반환한다. */
  private static String textOrDefault(String value, String defaultValue) {
    return value == null || value.isBlank() ? defaultValue : value;
  }

  /** roomOfferId를 API와 저장 스키마에서 사용하는 24자리 ObjectId hex 문자열로 정규화한다. */
  private static String roomOfferId(Object value) {
    if (value instanceof ObjectId objectId) {
      return objectId.toHexString();
    }
    if (value instanceof String text && ObjectId.isValid(text)) {
      return text;
    }
    return new ObjectId().toHexString();
  }

  /** forward-only: 저장 스키마 변경은 되돌리지 않는다. */
  @RollbackExecution
  public void rollback() {
    // no-op (forward-only)
  }
}
