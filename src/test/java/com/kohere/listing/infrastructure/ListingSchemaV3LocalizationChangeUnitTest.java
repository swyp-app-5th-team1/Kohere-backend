package com.kohere.listing.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;

/** {@link ListingSchemaV3LocalizationChangeUnit}의 문서 변환 규칙을 MongoDB 없이 빠르게 검증한다. */
class ListingSchemaV3LocalizationChangeUnitTest {

  /**
   * 사용자 표시 문구는 {ko,en}으로 바뀌지만 필터·정책·교통수단 코드는 원래 문자열을 유지해야 한다.
   *
   * <p>코드까지 번역해 버리면 기존 MongoDB 필터와 프론트 요청 값이 맞지 않으므로, 이 테스트가 두 종류의 데이터를 명확히 구분한다.
   */
  @Test
  void migrate_고유문구만_다국어로_바꾸고_공통코드는_보존한다() {
    Document migrated = ListingSchemaV3LocalizationChangeUnit.migrate(legacyListing());

    assertThat(migrated.getInteger("schemaVersion")).isEqualTo(3);
    assertLocalized(migrated.get("title", Document.class), "고시원001", "Goshiwon 001");
    assertThat(migrated.getString("type")).isEqualTo("GOSHIWON");
    assertThat(migrated.getString("genderPolicy")).isEqualTo("FEMALE_ONLY");

    Document address = migrated.get("address", Document.class);
    assertThat(address.getString("city")).isEqualTo("SEOUL");
    assertThat(address.getString("district")).isEqualTo("GWANAK_GU");
    assertLocalized(
        address.get("fullAddress", Document.class),
        "서울특별시 관악구 신림동 나로 56-15",
        "56-15 Naro, Sillim-dong, Gwanak-gu, Seoul");

    Document transit = migrated.get("nearestTransit", Document.class);
    assertThat(transit.getString("type")).isEqualTo("SUBWAY");
    assertLocalized(transit.get("name", Document.class), "서울대입구역", "Seoul Nat'l Univ. Station");
    assertLocalized(
        transit.get("nearbyPlacesDescription", Document.class),
        "CU, 스타벅스, 약국, 헬스장",
        "CU, Starbucks, pharmacy, gym");

    Document room = migrated.getList("roomOffers", Document.class).getFirst();
    assertLocalized(room.get("name", Document.class), "스탠다드 1인실", "Standard Single Room");
    assertThat(room.getList("filterTags", String.class))
        .containsExactly("FEMALE_ONLY", "NO_MAINT_FEE");
  }

  /** 레거시 시설 표시 문자열은 카탈로그 code로 정규화하고 잘못 분류된 샤워실은 공용공간으로 옮긴다. */
  @Test
  void migrate_레거시_시설문구를_표준코드와_올바른그룹으로_정규화한다() {
    Document migrated = ListingSchemaV3LocalizationChangeUnit.migrate(legacyListing());
    Document facilities = migrated.get("facilities", Document.class);

    assertThat(facilities.getList("kitchen", String.class))
        .containsExactly("MICROWAVE", "SHARED_REFRIGERATOR");
    assertThat(facilities.getList("providedSupplies", String.class))
        .containsExactly("SLIPPERS", "LAUNDRY_DETERGENT", "TISSUE");
    assertThat(facilities.getList("commonSpaces", Document.class))
        .extracting(space -> space.getString("type"))
        .containsExactly("SHARED_TOILET", "SHARED_BATH");
  }

  /** 이미 저장된 번역은 재실행해도 덮어쓰지 않고 결과가 완전히 동일해야 한다. */
  @Test
  void migrate_재실행해도_기존번역을_보존하는_멱등변환이다() {
    Document first = ListingSchemaV3LocalizationChangeUnit.migrate(legacyListing());
    first.get("title", Document.class).put("en", "Operator Edited Title");

    Document second = ListingSchemaV3LocalizationChangeUnit.migrate(first);

    assertThat(second).isEqualTo(first);
    assertThat(second.get("title", Document.class).getString("en"))
        .isEqualTo("Operator Edited Title");
  }

  /** 테스트에서 사용할 대표 v2 listings 문서를 만든다. */
  private static Document legacyListing() {
    return new Document("schemaVersion", 2)
        .append("title", "고시원001")
        .append("type", "GOSHIWON")
        .append("genderPolicy", "FEMALE_ONLY")
        .append(
            "address",
            new Document("city", "SEOUL")
                .append("district", "GWANAK_GU")
                .append("fullAddress", "서울특별시 Gwanak-gu Sillim-dong 나로 56-15")
                .append("detail", null))
        .append(
            "nearestTransit",
            new Document("type", "SUBWAY")
                .append("name", "Seoul Nat'l Univ.")
                .append("walkMinutes", 5)
                .append("nearbyPlacesDescription", "CU, 스타벅스, 약국, 헬스장"))
        .append(
            "refundPolicy",
            new Document("code", "FULL_REFUND_BEFORE_7_DAYS")
                .append("description", "입주 7일 전 취소 시 전액 환불"))
        .append(
            "descriptions",
            new Document("ko", "지하철역 도보 5분 이내, 교통이 편리한 위치의 코리빙 하우스입니다.")
                .append("en", "Incorrect legacy translation")
                .append("extraNotes", "외국인 환영, 영어 안내 가능합니다."))
        .append(
            "facilities",
            new Document("heatingSystem", List.of("CENTRAL"))
                .append("kitchen", List.of("Microwave", "Shared Refrigerator"))
                .append("laundry", List.of("COIN_LAUNDRY"))
                .append("livingAmenities", List.of("WIFI"))
                .append("securityFeatures", List.of("CCTV"))
                .append(
                    "commonSpaces",
                    List.of(new Document("type", "SHARED_TOILET").append("count", 6)))
                .append(
                    "providedSupplies",
                    List.of("Slippers", "Laundry Detergent", "Toilet Paper", "Shower Room")))
        .append(
            "roomOffers",
            List.of(
                new Document("roomOfferId", "6858e2000000000000000101")
                    .append("name", "스탠다드 1인실")
                    .append("filterTags", List.of("FEMALE_ONLY", "NO_MAINT_FEE"))));
  }

  /** {ko,en} 두 값을 읽기 쉬운 한 줄 assertion으로 확인한다. */
  private static void assertLocalized(Document text, String expectedKo, String expectedEn) {
    assertThat(text).isNotNull();
    assertThat(text.getString("ko")).isEqualTo(expectedKo);
    assertThat(text.getString("en")).isEqualTo(expectedEn);
  }
}
