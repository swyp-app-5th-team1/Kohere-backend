package com.kohere.listing.infrastructure.migration;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * listings 문서의 사용자 표시 문구를 한국어·영어를 함께 저장하는 v3 구조로 이행한다.
 *
 * <p>검색과 비즈니스 로직에 사용하는 {@code FEMALE_ONLY}, {@code SUBWAY}, {@code MICROWAVE} 같은 코드는 번역하지 않는다. 코드의
 * 화면 표시명은 별도 {@code listingCatalog} 컬렉션이 담당한다. 반면 매물명, 주소, 역명, 방 이름처럼 매물마다 달라지는 자유 문구는 해당 listings
 * 문서 안에서 {@code {ko, en}} 형태로 관리한다.
 *
 * <p>이 마이그레이션은 같은 문서에 여러 번 적용해도 이미 만들어진 다국어 값을 보존한다. 운영 중 수동으로 보완한 번역을 재기동 시 덮어쓰지 않기 위한 안전장치다.
 */
@ChangeUnit(id = "listing-schema-v3-localization", order = "0109", author = "kohere")
public class ListingSchemaV3LocalizationChangeUnit {

  private static final Pattern GOSHIWON_TITLE = Pattern.compile("고시원\\s*(\\d+)");

  /** v3가 아니거나 필수 다국어 하위 문서가 없는 listings 문서를 찾아 같은 id로 교체한다. */
  @Execution
  public void execution(MongoTemplate mongo) {
    MongoCollection<Document> listings = mongo.getCollection(ListingMigrationCollections.LISTINGS);
    for (Document listing : listings.find(legacyListingFilter())) {
      listings.replaceOne(
          Filters.eq("_id", listing.get("_id")),
          migrate(listing),
          new ReplaceOptions().upsert(false));
    }
  }

  /** 문자열 표시 필드가 남아 있는 문서까지 빠짐없이 찾는 v3 이행 조건이다. */
  static org.bson.conversions.Bson legacyListingFilter() {
    return Filters.or(
        Filters.ne("schemaVersion", 3),
        Filters.type("title", "string"),
        Filters.type("address.fullAddress", "string"),
        Filters.type("nearestTransit.name", "string"),
        Filters.type("nearestTransit.nearbyPlacesDescription", "string"),
        Filters.type("refundPolicy.description", "string"),
        Filters.type("roomOffers.name", "string"));
  }

  /** 문서 하나의 고유 표시 문구와 레거시 시설 문자열을 v3 정본으로 변환한다. */
  static Document migrate(Document source) {
    Document listing = new Document(source);

    listing.put(
        "title",
        localize(listing.get("title"), ListingSchemaV3LocalizationChangeUnit::titleEnglish));

    Document address = nestedDocument(listing, "address");
    address.put(
        "fullAddress",
        localize(
            address.get("fullAddress"),
            ListingSchemaV3LocalizationChangeUnit::addressKorean,
            ListingSchemaV3LocalizationChangeUnit::addressEnglish));
    if (address.get("detail") != null) {
      address.put("detail", localize(address.get("detail"), Function.identity()));
    }
    listing.put("address", address);

    Document transit = nestedDocument(listing, "nearestTransit");
    transit.put(
        "name",
        localize(
            transit.get("name"),
            ListingSchemaV3LocalizationChangeUnit::stationKorean,
            ListingSchemaV3LocalizationChangeUnit::stationEnglish));
    transit.put(
        "nearbyPlacesDescription",
        localize(
            transit.get("nearbyPlacesDescription"),
            ListingSchemaV3LocalizationChangeUnit::nearbyPlacesEnglish));
    listing.put("nearestTransit", transit);

    Document refundPolicy = nestedDocument(listing, "refundPolicy");
    refundPolicy.put(
        "description",
        localize(
            refundPolicy.get("description"),
            ListingSchemaV3LocalizationChangeUnit::refundPolicyEnglish));
    listing.put("refundPolicy", refundPolicy);

    migrateDescriptions(listing);
    migrateRoomNames(listing);
    normalizeFacilities(listing);
    listing.put("schemaVersion", 3);
    return listing;
  }

  /** descriptions의 기존 ko 값은 유지하고, 샘플에서 잘못 대응된 en 번역은 한국어 원문 기준으로 바로잡는다. */
  private static void migrateDescriptions(Document listing) {
    Document descriptions = nestedDocument(listing, "descriptions");
    String korean = nonBlank(descriptions.getString("ko"), "설명 없음");
    descriptions.put("ko", korean);
    descriptions.put(
        "en", descriptionEnglish(korean, nonBlank(descriptions.getString("en"), korean)));
    // extraNotes는 사용자가 이번 번역 범위에서 명시적으로 제외했으므로 기존 값을 그대로 보존한다.
    descriptions.putIfAbsent("extraNotes", "");
    listing.put("descriptions", descriptions);
  }

  /** 모든 방 상품의 사용자 표시 이름만 다국어 문서로 바꾸고 가격·재고·filterTags는 그대로 둔다. */
  private static void migrateRoomNames(Document listing) {
    List<Document> roomOffers = documentList(listing.get("roomOffers"));
    roomOffers.forEach(
        offer ->
            offer.put(
                "name",
                localize(
                    offer.get("name"), ListingSchemaV3LocalizationChangeUnit::roomNameEnglish)));
    listing.put("roomOffers", roomOffers);
  }

  /**
   * 과거 seed에 표시 문구로 들어간 시설을 카탈로그와 검색이 공유하는 대문자 코드로 정규화한다.
   *
   * <p>{@code Shower Room}은 제공 물품이 아니라 공용공간이므로 providedSupplies에서 제거하고 SHARED_BATH 공용공간으로 옮긴다.
   */
  private static void normalizeFacilities(Document listing) {
    Document facilities = nestedDocument(listing, "facilities");
    facilities.put("kitchen", normalizeCodes(facilities.get("kitchen")));
    facilities.put("laundry", normalizeCodes(facilities.get("laundry")));
    facilities.put("livingAmenities", normalizeCodes(facilities.get("livingAmenities")));
    facilities.put("securityFeatures", normalizeCodes(facilities.get("securityFeatures")));

    Set<String> supplies = new LinkedHashSet<>(normalizeCodes(facilities.get("providedSupplies")));
    boolean hadShowerRoom = supplies.remove("SHOWER_ROOM");
    facilities.put("providedSupplies", new ArrayList<>(supplies));

    List<Document> commonSpaces = documentList(facilities.get("commonSpaces"));
    boolean hasSharedBath =
        commonSpaces.stream().anyMatch(space -> "SHARED_BATH".equals(space.getString("type")));
    if (hadShowerRoom && !hasSharedBath) {
      commonSpaces.add(new Document("type", "SHARED_BATH").append("count", null));
    }
    facilities.put("commonSpaces", commonSpaces);
    listing.put("facilities", facilities);
  }

  /** 이미 {ko,en}이면 복사하고, 문자열이면 번역 함수를 사용해 새 다국어 문서를 만든다. */
  private static Document localize(Object value, Function<String, String> englishTranslator) {
    return localize(value, Function.identity(), englishTranslator);
  }

  /**
   * 레거시 문자열의 한국어 표기 자체도 정규화해야 할 때 사용하는 다국어 변환 함수다.
   *
   * <p>이미 다국어 문서인 값은 운영자가 수정한 번역일 수 있으므로 그대로 보존하고, 레거시 문자열일 때만 정규화 함수를 적용한다.
   */
  private static Document localize(
      Object value,
      Function<String, String> koreanNormalizer,
      Function<String, String> englishTranslator) {
    if (value instanceof Document localized) {
      String korean = nonBlank(localized.getString("ko"), localized.getString("en"));
      String english = nonBlank(localized.getString("en"), englishTranslator.apply(korean));
      return new Document("ko", korean).append("en", english);
    }
    String source = nonBlank(value == null ? null : value.toString(), "정보 없음");
    String korean = nonBlank(koreanNormalizer.apply(source), source);
    return new Document("ko", korean)
        .append("en", nonBlank(englishTranslator.apply(korean), korean));
  }

  /** 레거시 공백 포함 시설명을 API와 저장소가 사용하는 표준 코드로 바꾼다. */
  private static List<String> normalizeCodes(Object value) {
    if (!(value instanceof List<?> values)) {
      return List.of();
    }
    LinkedHashSet<String> normalized = new LinkedHashSet<>();
    for (Object item : values) {
      if (item == null) {
        continue;
      }
      normalized.add(
          switch (item.toString().trim()) {
            case "Microwave" -> "MICROWAVE";
            case "Shared Refrigerator" -> "SHARED_REFRIGERATOR";
            case "Slippers" -> "SLIPPERS";
            case "Laundry Detergent" -> "LAUNDRY_DETERGENT";
            case "Toilet Paper" -> "TISSUE";
            case "Shower Room" -> "SHOWER_ROOM";
            default -> item.toString().trim();
          });
    }
    return new ArrayList<>(normalized);
  }

  /** 현재 seed와 운영 샘플에서 사용하는 매물명 번역이다. 번호형 이름은 같은 규칙으로 계속 확장된다. */
  private static String titleEnglish(String korean) {
    Matcher matcher = GOSHIWON_TITLE.matcher(korean);
    return matcher.matches() ? "Goshiwon " + matcher.group(1) : korean;
  }

  /** 현재 DB 샘플의 주소를 자연스러운 영문 순서로 변환한다. 알 수 없는 값은 원문을 보존해 데이터 손실을 막는다. */
  private static String addressEnglish(String korean) {
    return switch (korean) {
      case "서울특별시 Gwanak-gu Sillim-dong 나로 56-15", "서울특별시 관악구 신림동 나로 56-15" ->
          "56-15 Naro, Sillim-dong, Gwanak-gu, Seoul";
      case "서울특별시 Gwanak-gu Sillim-dong 가로 195-11", "서울특별시 관악구 신림동 가로 195-11" ->
          "195-11 Garo, Sillim-dong, Gwanak-gu, Seoul";
      case "서울특별시 Gwanak-gu Sillim-dong 바로 155-17", "서울특별시 관악구 신림동 바로 155-17" ->
          "155-17 Baro, Sillim-dong, Gwanak-gu, Seoul";
      case "서울특별시 Gwanak-gu Sillim-dong 라로 198-19", "서울특별시 관악구 신림동 라로 198-19" ->
          "198-19 Raro, Sillim-dong, Gwanak-gu, Seoul";
      case "서울특별시 Gwanak-gu Sillim-dong 라로 172-18", "서울특별시 관악구 신림동 라로 172-18" ->
          "172-18 Raro, Sillim-dong, Gwanak-gu, Seoul";
      case "서울특별시 Gwanak-gu Sillim-dong 자길 57-44", "서울특별시 관악구 신림동 자길 57-44" ->
          "57-44 Jagil, Sillim-dong, Gwanak-gu, Seoul";
      case "서울특별시 Gwanak-gu Sillim-dong 차로 184-21", "서울특별시 관악구 신림동 차로 184-21" ->
          "184-21 Charo, Sillim-dong, Gwanak-gu, Seoul";
      case "서울특별시 Gwanak-gu Sillim-dong 바로 57-9", "서울특별시 관악구 신림동 바로 57-9" ->
          "57-9 Baro, Sillim-dong, Gwanak-gu, Seoul";
      case "서울특별시 Gwanak-gu Sillim-dong 차로 120-34", "서울특별시 관악구 신림동 차로 120-34" ->
          "120-34 Charo, Sillim-dong, Gwanak-gu, Seoul";
      case "서울특별시 Gwanak-gu Sillim-dong 다길 117-1", "서울특별시 관악구 신림동 다길 117-1" ->
          "117-1 Dagil, Sillim-dong, Gwanak-gu, Seoul";
      default -> korean;
    };
  }

  /** 영문 행정구역이 섞여 있던 현재 샘플 주소를 일관된 한국어 표기로 정규화한다. */
  private static String addressKorean(String address) {
    return address.replace("Gwanak-gu", "관악구").replace("Sillim-dong", "신림동");
  }

  /** 과거 영문 역명 문자열을 한국어 표시명으로 정규화한다. */
  private static String stationKorean(String name) {
    return switch (name) {
      case "Seoul Nat'l Univ.", "Seoul Nat'l Univ. Station" -> "서울대입구역";
      case "Sillim", "Sillim Station" -> "신림역";
      case "Nakseongdae", "Nakseongdae Station" -> "낙성대역";
      case "Bongcheon", "Bongcheon Station" -> "봉천역";
      default -> name;
    };
  }

  /** 역 이름은 화면용 이름으로 번역하며 SUBWAY/BUS 타입 코드는 별도 카탈로그가 처리한다. */
  private static String stationEnglish(String name) {
    return switch (name) {
      case "서울대입구역", "Seoul Nat'l Univ." -> "Seoul Nat'l Univ. Station";
      case "신림역", "Sillim" -> "Sillim Station";
      case "낙성대역", "Nakseongdae" -> "Nakseongdae Station";
      case "봉천역", "Bongcheon" -> "Bongcheon Station";
      default -> name;
    };
  }

  /** 주변 편의시설 자유 문구의 현재 데이터 번역이다. */
  private static String nearbyPlacesEnglish(String text) {
    return switch (text) {
      case "CU, 스타벅스, 약국, 헬스장" -> "CU, Starbucks, pharmacy, gym";
      case "GS25, 맥도날드, 약국, 지하철역" -> "GS25, McDonald's, pharmacy, subway station";
      case "GS25, CU, 카페, 약국" -> "GS25, CU, cafe, pharmacy";
      default -> text;
    };
  }

  /** 환불 정책 코드와 별개로 사용자에게 표시할 상세 문장을 번역한다. */
  private static String refundPolicyEnglish(String text) {
    return switch (text) {
      case "입주 7일 전 취소 시 전액 환불" ->
          "Full refund for cancellations made at least 7 days before move-in.";
      case "입주 3일 전 취소 시 50% 환불" ->
          "50% refund for cancellations made at least 3 days before move-in.";
      case "1개월 이상 거주 후 해지 시 잔여 월세 환불" ->
          "Remaining rent refunded when ending the contract after at least one month of stay.";
      case "입주 당일 취소 시 환불 불가" -> "No refund for cancellations made on the move-in date.";
      default -> text;
    };
  }

  /** 방 이름 번역이다. 새 이름이 추가되면 이행 스크립트가 아니라 등록 시 입력된 en 값을 사용한다. */
  private static String roomNameEnglish(String text) {
    return switch (text) {
      case "스탠다드 1인실" -> "Standard Single Room";
      case "스탠다드 2인실" -> "Standard Double Room";
      case "여성전용 넓은 1인실" -> "Spacious Female-Only Single Room";
      default -> text;
    };
  }

  /** 기존 descriptions.en이 잘못 매칭된 샘플을 ko 원문 기준의 정확한 번역으로 교정한다. */
  private static String descriptionEnglish(String korean, String existingEnglish) {
    return switch (korean) {
      case "지하철역 도보 5분 이내, 교통이 편리한 위치의 코리빙 하우스입니다." ->
          "A co-living house in a convenient location within a five-minute walk of a subway station.";
      case "CCTV 및 도어락 설치로 안전하며, 즉시 입주 가능한 매물입니다." ->
          "A secure listing with CCTV and door locks, available for immediate move-in.";
      case "여성 전용 건물로 안전하고 조용한 주거 환경을 제공합니다." ->
          "A women-only property offering a safe and quiet living environment.";
      case "깔끔하게 관리되는 고시원으로, 외국인 입주자도 편하게 생활하실 수 있습니다." ->
          "A well-maintained gosiwon where international residents can live comfortably.";
      default -> existingEnglish;
    };
  }

  /** Document 하위 값을 복사해 반환하여 원본 중첩 객체를 직접 수정하지 않는다. */
  private static Document nestedDocument(Document document, String key) {
    Object value = document.get(key);
    return value instanceof Document nested ? new Document(nested) : new Document();
  }

  /** Document 배열을 방어적으로 복사한다. */
  private static List<Document> documentList(Object value) {
    if (!(value instanceof List<?> values)) {
      return new ArrayList<>();
    }
    List<Document> documents = new ArrayList<>();
    for (Object item : values) {
      if (item instanceof Document document) {
        documents.add(new Document(document));
      }
    }
    return documents;
  }

  /** null 또는 공백 문자열 대신 안전한 대체값을 반환한다. */
  private static String nonBlank(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  /** forward-only: 번역 구조로 바뀐 운영 데이터를 문자열 스키마로 되돌리지 않는다. */
  @RollbackExecution
  public void rollback() {
    // no-op
  }
}
