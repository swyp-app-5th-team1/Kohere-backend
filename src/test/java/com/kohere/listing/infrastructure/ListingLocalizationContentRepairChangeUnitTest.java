package com.kohere.listing.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import org.bson.Document;
import org.junit.jupiter.api.Test;

/** {@link ListingLocalizationContentRepairChangeUnit}의 기존 매물 표시 문구 보정 규칙을 검증한다. */
class ListingLocalizationContentRepairChangeUnitTest {

  /**
   * 실제 문제 화면에 노출된 혼합 주소와 한국어 주변시설이 합의한 UI 문구로 바뀌어야 한다.
   *
   * <p>이마트24는 브랜드명이 아니라 시설 종류인 Convenience Store로, 세탁소는 Laundry Service로 표시한다.
   */
  @Test
  void repair_혼합주소와_한국어주변시설을_UI영문으로_보정한다() {
    Document source =
        listing(
            "서울특별시 Dongdaemun-gu Hoegi-dong 차로 21-6",
            "서울특별시 Dongdaemun-gu Hoegi-dong 차로 21-6",
            "이마트24, 세탁소, 카페, 병원",
            "이마트24, 세탁소, 카페, 병원");

    Document repaired = ListingLocalizationContentRepairChangeUnit.repair(source);

    assertLocalized(
        fullAddress(repaired),
        "서울특별시 동대문구 회기동 차로 21-6",
        "21-6 Cha-ro, Hoegi-dong, Dongdaemun-gu, Seoul");
    assertLocalized(
        nearbyPlaces(repaired),
        "이마트24, 세탁소, 카페, 병원",
        "Convenience Store, Laundry Service, Cafe, Hospital");

    // 순수 변환은 원본을 직접 수정하지 않아야 실행 전후 비교와 재시도가 안전하다.
    assertLocalized(
        fullAddress(source),
        "서울특별시 Dongdaemun-gu Hoegi-dong 차로 21-6",
        "서울특별시 Dongdaemun-gu Hoegi-dong 차로 21-6");
  }

  /** 숫자가 포함된 실제 도로명도 건물번호와 혼동하지 않고 자연스러운 영문 순서로 조합한다. */
  @Test
  void repair_숫자가_포함된_도로명주소를_올바른_순서로_보정한다() {
    Document source =
        listing(
            "서울특별시 Guro-gu Guro-dong 디지털로32길 9",
            "서울특별시 Guro-gu Guro-dong 디지털로32길 9",
            "신도림역, 쇼핑몰, 편의점, 영화관",
            "신도림역, 쇼핑몰, 편의점, 영화관");

    Document repaired = ListingLocalizationContentRepairChangeUnit.repair(source);

    assertLocalized(
        fullAddress(repaired),
        "서울특별시 구로구 구로동 디지털로32길 9",
        "9 Digital-ro 32-gil, Guro-dong, Guro-gu, Seoul");
    assertLocalized(
        nearbyPlaces(repaired),
        "신도림역, 쇼핑몰, 편의점, 영화관",
        "Sindorim Station, Shopping Mall, Convenience Store, Movie Theater");
  }

  /** 동이 없는 주소는 동을 억지로 만들지 않고 존재하는 구성요소만으로 영문 주소를 만든다. */
  @Test
  void repair_동이_없는_주소는_동을_강제하지_않는다() {
    Document source = listing("서울특별시 마포구 양화로 45", "서울특별시 마포구 양화로 45", "CU, 카페, 약국", "CU, 카페, 약국");

    Document repaired = ListingLocalizationContentRepairChangeUnit.repair(source);

    assertLocalized(fullAddress(repaired), "서울특별시 마포구 양화로 45", "45 Yanghwa-ro, Mapo-gu, Seoul");
    assertLocalized(nearbyPlaces(repaired), "CU, 카페, 약국", "CU, Cafe, Pharmacy");
  }

  /** 이미 영문이 정상이고 지원 사전에 없는 주소는 추측으로 다시 쓰지 않고 그대로 보존한다. */
  @Test
  void repair_지원하지_않는_정상주소는_그대로_보존한다() {
    Document source =
        listing(
                "부산광역시 해운대구 우동 해운대로 100",
                "100 Haeundae-ro, U-dong, Haeundae-gu, Busan",
                "Beach, Restaurant",
                "Beach, Restaurant")
            .append("favoriteCount", 7);

    Document repaired = ListingLocalizationContentRepairChangeUnit.repair(source);

    assertThat(repaired).isEqualTo(source);
  }

  /** 이미 보정된 문서를 다시 처리해도 값이 달라지지 않아 배포 재시도에 안전해야 한다. */
  @Test
  void repair_재실행해도_동일한_멱등변환이다() {
    Document source =
        listing(
            "서울특별시 동대문구 회기동 차로 21-6",
            "21-6 Cha-ro, Hoegi-dong, Dongdaemun-gu, Seoul",
            "이마트24, 세탁소, 카페, 병원",
            "Convenience Store, Laundry Service, Cafe, Hospital");

    Document first = ListingLocalizationContentRepairChangeUnit.repair(source);
    Document second = ListingLocalizationContentRepairChangeUnit.repair(first);

    assertThat(second).isEqualTo(first);
  }

  /** 테스트에 필요한 v3 listings 문서의 주소·교통 하위 구조를 만든다. */
  private static Document listing(
      String addressKo, String addressEn, String nearbyKo, String nearbyEn) {
    return new Document("_id", "listing-id")
        .append(
            "address",
            new Document("fullAddress", new Document("ko", addressKo).append("en", addressEn)))
        .append(
            "nearestTransit",
            new Document(
                "nearbyPlacesDescription", new Document("ko", nearbyKo).append("en", nearbyEn)));
  }

  /** 보정된 주소의 다국어 하위 문서를 읽는다. */
  private static Document fullAddress(Document listing) {
    return listing.get("address", Document.class).get("fullAddress", Document.class);
  }

  /** 보정된 주변시설의 다국어 하위 문서를 읽는다. */
  private static Document nearbyPlaces(Document listing) {
    return listing
        .get("nearestTransit", Document.class)
        .get("nearbyPlacesDescription", Document.class);
  }

  /** {@code {ko,en}} 두 정본을 읽기 쉬운 한 assertion으로 검증한다. */
  private static void assertLocalized(
      Document localized, String expectedKorean, String expectedEnglish) {
    assertThat(localized).containsEntry("ko", expectedKorean).containsEntry("en", expectedEnglish);
  }
}
