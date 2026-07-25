package com.kohere.listing.infrastructure;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * v3 이행 당시 영어 칸에 한국어 원문이 남은 기존 매물의 주소와 주변시설 문구를 보정한다.
 *
 * <p>0109 마이그레이션은 레거시 문자열을 {@code {ko,en}} 구조로 바꾸면서 미리 등록된 일부 문구만 영어로 치환했다. 치환 목록에 없던 값은 데이터 손실을 막기
 * 위해 원문을 {@code en}에도 저장했기 때문에, 외국인 화면에서 한국어 또는 한·영 혼합 문구가 그대로 노출됐다.
 *
 * <p>이미 실행된 0109를 수정하면 기존 환경에서는 다시 실행되지 않으므로, 이 변경 단위가 0113에서 기존 v3 문서의 내용만 보정한다. 문서 전체를 교체하지 않고
 * {@code address.fullAddress}와 {@code nearestTransit.nearbyPlacesDescription}만 {@code $set}하여 가격,
 * 좌표, 재고 등 다른 매물 정보는 보존한다.
 */
@ChangeUnit(id = "listing-localization-content-repair", order = "0113", author = "kohere")
public class ListingLocalizationContentRepairChangeUnit {

  /**
   * 현재 서울 매물의 한국어 주소를 구성요소로 분리하는 패턴이다.
   *
   * <p>주소는 {@code 서울특별시 + 구 + (선택적 동) + 도로명 + 건물번호} 순서로 저장되어 있다. 동은 화면에 유용할 때 유지하되 필수로 강제하지 않는다.
   * 마지막 건물번호를 기준으로 도로명을 분리하므로 {@code 디지털로32길}처럼 숫자가 포함된 도로명도 처리할 수 있다.
   */
  private static final Pattern SEOUL_ADDRESS =
      Pattern.compile(
          "^(?:서울특별시|서울|Seoul)\\s+(\\S+)"
              + "(?:\\s+(\\S+(?:-dong|동)))?\\s+(.+?)\\s+(\\d+(?:-\\d+)?)$",
          Pattern.CASE_INSENSITIVE);

  private static final Pattern HANGUL = Pattern.compile("[가-힣]");

  private static final Map<String, LocalizedComponent> DISTRICTS = districtNames();
  private static final Map<String, LocalizedComponent> NEIGHBORHOODS = neighborhoodNames();
  private static final Map<String, LocalizedComponent> ROADS = roadNames();
  private static final Map<String, String> NEARBY_PLACE_TRANSLATIONS = nearbyPlaceTranslations();

  /**
   * 모든 listings 문서를 읽되 실제 값이 달라진 두 필드만 부분 갱신한다.
   *
   * <p>이미 올바른 문서는 MongoDB 쓰기 자체를 생략한다. 같은 보정 규칙을 다시 적용해도 결과가 달라지지 않으므로, 배포 중 일부 문서까지만 처리된 뒤 재시도되더라도
   * 안전하다.
   */
  @Execution
  public void execution(MongoTemplate mongo) {
    MongoCollection<Document> listings = mongo.getCollection(ListingDocument.COLLECTION_NAME);

    for (Document source : listings.find()) {
      Document repaired = repair(source);
      Document fieldsToSet = changedLocalizedFields(source, repaired);
      if (fieldsToSet.isEmpty()) {
        continue;
      }
      listings.updateOne(Filters.eq("_id", source.get("_id")), new Document("$set", fieldsToSet));
    }
  }

  /**
   * 문서 하나의 주소와 주변시설 다국어 값만 보정한 복사본을 반환한다.
   *
   * <p>MongoDB 없이 단위 테스트할 수 있도록 순수 변환으로 분리했다. 입력 문서와 입력의 중첩 문서를 직접 수정하지 않아 반복 순회 중 부작용이 생기지 않는다.
   */
  static Document repair(Document source) {
    Document repaired = new Document(source);

    Document address = copiedNestedDocument(source, "address");
    if (address != null) {
      Document fullAddress = copiedLocalizedText(address.get("fullAddress"));
      if (fullAddress != null) {
        address.put("fullAddress", repairAddress(fullAddress));
      }
      repaired.put("address", address);
    }

    Document nearestTransit = copiedNestedDocument(source, "nearestTransit");
    if (nearestTransit != null) {
      Document nearbyPlaces = copiedLocalizedText(nearestTransit.get("nearbyPlacesDescription"));
      if (nearbyPlaces != null) {
        nearestTransit.put("nearbyPlacesDescription", repairNearbyPlacesDescription(nearbyPlaces));
      }
      repaired.put("nearestTransit", nearestTransit);
    }

    return repaired;
  }

  /**
   * 한국어 주소를 앱의 영문 표시 순서인 {@code 건물번호 도로명, 동, 구, Seoul}로 조합한다.
   *
   * <p>구·동·도로명 정본을 모두 알고 있을 때만 값을 바꾼다. 알 수 없는 구성요소를 억지로 음역하면 실제 주소를 훼손할 수 있으므로, 지원하지 않는 주소는 기존 값을
   * 그대로 보존한다. 동이 원문에 없으면 영문 결과에서도 생략한다.
   */
  private static Document repairAddress(Document current) {
    String source = firstNonBlank(current.getString("ko"), current.getString("en"));
    if (source == null) {
      return current;
    }

    Matcher matcher = SEOUL_ADDRESS.matcher(normalizeSpaces(source));
    if (!matcher.matches()) {
      return current;
    }

    LocalizedComponent district = DISTRICTS.get(normalizedKey(matcher.group(1)));
    String neighborhoodToken = matcher.group(2);
    LocalizedComponent neighborhood =
        neighborhoodToken == null ? null : NEIGHBORHOODS.get(normalizedKey(neighborhoodToken));
    LocalizedComponent road = ROADS.get(normalizedKey(matcher.group(3)));
    String buildingNumber = matcher.group(4);

    if (district == null || road == null || (neighborhoodToken != null && neighborhood == null)) {
      return current;
    }

    List<String> koreanParts = new ArrayList<>();
    koreanParts.add("서울특별시");
    koreanParts.add(district.ko());
    if (neighborhood != null) {
      koreanParts.add(neighborhood.ko());
    }
    koreanParts.add(road.ko());
    koreanParts.add(buildingNumber);

    List<String> englishAreas = new ArrayList<>();
    if (neighborhood != null) {
      englishAreas.add(neighborhood.en());
    }
    englishAreas.add(district.en());
    englishAreas.add("Seoul");

    Document repaired = new Document(current);
    repaired.put("ko", String.join(" ", koreanParts));
    repaired.put("en", buildingNumber + " " + road.en() + ", " + String.join(", ", englishAreas));
    return repaired;
  }

  /**
   * 쉼표로 구분된 주변시설을 UI에서 그대로 표시할 수 있는 영어 항목으로 바꾼다.
   *
   * <p>브랜드와 일반 시설이 섞여 있으므로 문장 전체를 비교하지 않고 항목별로 번역한다. 사용자 표시 정책에 따라 {@code 이마트24}는 {@code
   * Convenience Store}, {@code 세탁소}는 {@code Laundry Service}로 통일한다. 한글 항목이 사전에 없으면 잘못된 추측으로 일부만
   * 번역하지 않고 기존 값을 보존한다.
   */
  private static Document repairNearbyPlacesDescription(Document current) {
    String korean = firstNonBlank(current.getString("ko"), current.getString("en"));
    if (korean == null) {
      return current;
    }

    List<String> translated = new ArrayList<>();
    for (String rawItem : korean.split(",")) {
      String item = rawItem.trim();
      if (item.isEmpty()) {
        continue;
      }

      String english = NEARBY_PLACE_TRANSLATIONS.get(normalizedKey(item));
      if (english != null) {
        translated.add(english);
      } else if (HANGUL.matcher(item).find()) {
        return current;
      } else {
        // CU, GS25처럼 번역할 필요가 없는 영문 브랜드는 원래 표기를 유지한다.
        translated.add(item);
      }
    }

    if (translated.isEmpty()) {
      return current;
    }

    Document repaired = new Document(current);
    repaired.put("ko", korean);
    repaired.put("en", String.join(", ", translated));
    return repaired;
  }

  /**
   * 원본과 보정본을 비교해 MongoDB의 {@code $set}에 넣을 변경 필드만 만든다.
   *
   * <p>필드 경로를 직접 지정하므로 동일 문서의 다른 중첩 값을 실수로 덮어쓰지 않는다.
   */
  private static Document changedLocalizedFields(Document source, Document repaired) {
    Document fields = new Document();
    putIfChanged(
        fields,
        "address.fullAddress",
        nestedValue(source, "address", "fullAddress"),
        nestedValue(repaired, "address", "fullAddress"));
    putIfChanged(
        fields,
        "nearestTransit.nearbyPlacesDescription",
        nestedValue(source, "nearestTransit", "nearbyPlacesDescription"),
        nestedValue(repaired, "nearestTransit", "nearbyPlacesDescription"));
    return fields;
  }

  /** 원본과 값이 다르고 보정값이 존재할 때만 부분 갱신 목록에 추가한다. */
  private static void putIfChanged(
      Document fields, String path, Object originalValue, Object repairedValue) {
    if (repairedValue != null && !Objects.equals(originalValue, repairedValue)) {
      fields.put(path, repairedValue);
    }
  }

  /** 두 단계 중첩 경로의 값을 안전하게 읽는다. */
  private static Object nestedValue(Document source, String parentKey, String childKey) {
    Object parent = source.get(parentKey);
    return parent instanceof Document document ? document.get(childKey) : null;
  }

  /** 입력 문서의 중첩 객체를 직접 수정하지 않도록 복사한다. */
  private static Document copiedNestedDocument(Document source, String key) {
    Object value = source.get(key);
    return value instanceof Document document ? new Document(document) : null;
  }

  /** {@code {ko,en}} 모양의 값만 방어적으로 복사하며, 예상하지 못한 레거시 값은 건드리지 않는다. */
  private static Document copiedLocalizedText(Object value) {
    return value instanceof Document document ? new Document(document) : null;
  }

  /** 연속 공백과 앞뒤 공백을 제거해 주소 파싱이 데이터 입력 방식에 흔들리지 않게 한다. */
  private static String normalizeSpaces(String value) {
    return value.trim().replaceAll("\\s+", " ");
  }

  /** 영문 별칭은 대소문자 차이를 무시하고, 한국어 별칭은 그대로 사용할 수 있는 사전 키를 만든다. */
  private static String normalizedKey(String value) {
    return normalizeSpaces(value).toLowerCase(Locale.ROOT);
  }

  /** null·공백을 제외하고 가장 먼저 사용할 수 있는 문자열을 반환한다. */
  private static String firstNonBlank(String primary, String fallback) {
    if (primary != null && !primary.isBlank()) {
      return primary;
    }
    return fallback == null || fallback.isBlank() ? null : fallback;
  }

  /** 현재 매물 데이터에서 사용하는 서울의 구 이름과 영문 정본이다. */
  private static Map<String, LocalizedComponent> districtNames() {
    Map<String, LocalizedComponent> names = new LinkedHashMap<>();
    addAliases(names, new LocalizedComponent("관악구", "Gwanak-gu"), "관악구", "Gwanak-gu");
    addAliases(names, new LocalizedComponent("동대문구", "Dongdaemun-gu"), "동대문구", "Dongdaemun-gu");
    addAliases(names, new LocalizedComponent("금천구", "Geumcheon-gu"), "금천구", "Geumcheon-gu");
    addAliases(names, new LocalizedComponent("구로구", "Guro-gu"), "구로구", "Guro-gu");
    addAliases(names, new LocalizedComponent("광진구", "Gwangjin-gu"), "광진구", "Gwangjin-gu");
    addAliases(names, new LocalizedComponent("종로구", "Jongno-gu"), "종로구", "Jongno-gu");
    addAliases(names, new LocalizedComponent("마포구", "Mapo-gu"), "마포구", "Mapo-gu");
    addAliases(names, new LocalizedComponent("서대문구", "Seodaemun-gu"), "서대문구", "Seodaemun-gu");
    addAliases(names, new LocalizedComponent("영등포구", "Yeongdeungpo-gu"), "영등포구", "Yeongdeungpo-gu");
    return Map.copyOf(names);
  }

  /** 현재 매물 데이터에서 사용하는 동 이름과 영문 정본이다. */
  private static Map<String, LocalizedComponent> neighborhoodNames() {
    Map<String, LocalizedComponent> names = new LinkedHashMap<>();
    addAliases(names, new LocalizedComponent("신림동", "Sillim-dong"), "신림동", "Sillim-dong");
    addAliases(names, new LocalizedComponent("봉천동", "Bongcheon-dong"), "봉천동", "Bongcheon-dong");
    addAliases(names, new LocalizedComponent("회기동", "Hoegi-dong"), "회기동", "Hoegi-dong");
    addAliases(names, new LocalizedComponent("제기동", "Jegi-dong"), "제기동", "Jegi-dong");
    addAliases(names, new LocalizedComponent("가산동", "Gasan-dong"), "가산동", "Gasan-dong");
    addAliases(names, new LocalizedComponent("독산동", "Doksan-dong"), "독산동", "Doksan-dong");
    addAliases(names, new LocalizedComponent("구로동", "Guro-dong"), "구로동", "Guro-dong");
    addAliases(names, new LocalizedComponent("신도림동", "Sindorim-dong"), "신도림동", "Sindorim-dong");
    addAliases(names, new LocalizedComponent("대림동", "Daerim-dong"), "대림동", "Daerim-dong");
    addAliases(names, new LocalizedComponent("여의도동", "Yeouido-dong"), "여의도동", "Yeouido-dong");
    addAliases(names, new LocalizedComponent("혜화동", "Hyehwa-dong"), "혜화동", "Hyehwa-dong");
    addAliases(names, new LocalizedComponent("화양동", "Hwayang-dong"), "화양동", "Hwayang-dong");
    addAliases(names, new LocalizedComponent("합정동", "Hapjeong-dong"), "합정동", "Hapjeong-dong");
    return Map.copyOf(names);
  }

  /** 현재 매물 데이터에서 사용하는 실제·샘플 도로명과 UI용 영문 정본이다. */
  private static Map<String, LocalizedComponent> roadNames() {
    Map<String, LocalizedComponent> names = new LinkedHashMap<>();
    addAliases(names, new LocalizedComponent("가로", "Ga-ro"), "가로", "Ga-ro", "Garo");
    addAliases(names, new LocalizedComponent("나로", "Na-ro"), "나로", "Na-ro", "Naro");
    addAliases(names, new LocalizedComponent("다길", "Da-gil"), "다길", "Da-gil", "Dagil");
    addAliases(names, new LocalizedComponent("라로", "Ra-ro"), "라로", "Ra-ro", "Raro");
    addAliases(names, new LocalizedComponent("마길", "Ma-gil"), "마길", "Ma-gil", "Magil");
    addAliases(names, new LocalizedComponent("바로", "Ba-ro"), "바로", "Ba-ro", "Baro");
    addAliases(names, new LocalizedComponent("사길", "Sa-gil"), "사길", "Sa-gil", "Sagil");
    addAliases(names, new LocalizedComponent("아로", "A-ro"), "아로", "A-ro", "Aro");
    addAliases(names, new LocalizedComponent("자길", "Ja-gil"), "자길", "Ja-gil", "Jagil");
    addAliases(names, new LocalizedComponent("차로", "Cha-ro"), "차로", "Cha-ro", "Charo");
    addAliases(names, new LocalizedComponent("회기로", "Hoegi-ro"), "회기로", "Hoegi-ro");
    addAliases(names, new LocalizedComponent("관악로", "Gwanak-ro"), "관악로", "Gwanak-ro");
    addAliases(names, new LocalizedComponent("신림로", "Sillim-ro"), "신림로", "Sillim-ro");
    addAliases(
        names,
        new LocalizedComponent("디지털로32길", "Digital-ro 32-gil"),
        "디지털로32길",
        "Digital-ro 32-gil");
    addAliases(names, new LocalizedComponent("경인로", "Gyeongin-ro"), "경인로", "Gyeongin-ro");
    addAliases(names, new LocalizedComponent("도림로", "Dorim-ro"), "도림로", "Dorim-ro");
    addAliases(
        names,
        new LocalizedComponent("가산디지털1로", "Gasan digital 1-ro"),
        "가산디지털1로",
        "Gasan digital 1-ro");
    addAliases(names, new LocalizedComponent("시흥대로", "Siheung-daero"), "시흥대로", "Siheung-daero");
    addAliases(names, new LocalizedComponent("안암로", "Anam-ro"), "안암로", "Anam-ro");
    addAliases(names, new LocalizedComponent("국회대로", "Gukhoe-daero"), "국회대로", "Gukhoe-daero");
    addAliases(names, new LocalizedComponent("양화로", "Yanghwa-ro"), "양화로", "Yanghwa-ro");
    return Map.copyOf(names);
  }

  /** 현재 주변시설 원문을 화면에 바로 표시할 Title Case 영문으로 바꾸는 사전이다. */
  private static Map<String, String> nearbyPlaceTranslations() {
    Map<String, String> translations = new LinkedHashMap<>();
    addTranslations(
        translations, "Convenience Store", "이마트24", "Emart24", "편의점", "Convenience Store");
    addTranslations(translations, "Laundry Service", "세탁소", "Laundromat", "Laundry Service");
    addTranslations(translations, "Cafe", "카페", "Cafe");
    addTranslations(translations, "Hospital", "병원", "Hospital");
    addTranslations(translations, "Starbucks", "스타벅스", "Starbucks");
    addTranslations(translations, "Pharmacy", "약국", "Pharmacy");
    addTranslations(translations, "Gym", "헬스장", "Gym");
    addTranslations(translations, "McDonald's", "맥도날드", "McDonald's");
    addTranslations(translations, "Subway Station", "지하철역", "Subway Station");
    addTranslations(translations, "Kyung Hee University", "경희대", "Kyung Hee University");
    addTranslations(translations, "Korea University", "고려대", "Korea University");
    addTranslations(translations, "Restaurants", "식당가", "Restaurants");
    addTranslations(translations, "Park", "공원", "Park");
    addTranslations(translations, "Daerim Station", "대림역", "Daerim Station");
    addTranslations(translations, "Traditional Market", "전통시장", "Traditional Market");
    addTranslations(translations, "Bus Stop", "버스정류장", "Bus Stop");
    addTranslations(translations, "Supermarket", "마트", "대형마트", "Supermarket");
    addTranslations(translations, "Market", "시장", "Market");
    addTranslations(translations, "Sindorim Station", "신도림역", "Sindorim Station");
    addTranslations(translations, "Shopping Mall", "쇼핑몰", "Shopping Mall");
    addTranslations(translations, "Movie Theater", "영화관", "Movie Theater");
    addTranslations(translations, "Bank", "은행", "Bank");
    addTranslations(translations, "Hangang Park", "한강공원", "Hangang Park");
    return Map.copyOf(translations);
  }

  /** 하나의 한국어·영어 정본을 여러 과거 표기에서 찾을 수 있도록 별칭을 등록한다. */
  private static void addAliases(
      Map<String, LocalizedComponent> target, LocalizedComponent component, String... aliases) {
    for (String alias : aliases) {
      target.put(normalizedKey(alias), component);
    }
  }

  /** 같은 UI 영문으로 통일할 한국어·과거 영문 표기를 사전에 등록한다. */
  private static void addTranslations(
      Map<String, String> target, String english, String... sourceValues) {
    for (String sourceValue : sourceValues) {
      target.put(normalizedKey(sourceValue), english);
    }
  }

  /** 주소 구성요소 하나의 한국어·영어 정본이다. */
  private record LocalizedComponent(String ko, String en) {}

  /**
   * 잘못된 과거 번역을 자동 복원하지 않는 forward-only 변경이다.
   *
   * <p>운영 적용 전 대상 listings 문서를 백업하고, 문제가 생기면 백업본으로 복구한다.
   */
  @RollbackExecution
  public void rollback() {
    // no-op
  }
}
