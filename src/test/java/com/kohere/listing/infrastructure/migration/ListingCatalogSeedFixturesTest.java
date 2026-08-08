package com.kohere.listing.infrastructure.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.kohere.listing.domain.catalog.ListingCatalogCategory;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** listingCatalog 정본이 중복 없이 전체 UI 카테고리를 포함하는지 검증한다. */
class ListingCatalogSeedFixturesTest {

  /** category+code 조합은 유일하고 모든 문서에 ko/en 표시명이 있어야 한다. */
  @Test
  void documents_모든항목의_식별자와_두언어라벨이_유효하다() {
    Set<String> uniqueKeys = new HashSet<>();

    for (ListingCatalogSeedDocument document : ListingCatalogSeedFixtures.documents()) {
      String key = document.getCategory().name() + ":" + document.getCode();
      assertThat(uniqueKeys.add(key)).as("중복 카탈로그 키: %s", key).isTrue();
      assertThat(document.getId()).isEqualTo(key);
      assertThat(document.getLabel()).containsKeys("ko", "en");
      assertThat(document.getLabel().get("ko")).isNotBlank();
      assertThat(document.getLabel().get("en")).isNotBlank();
    }
  }

  /** 상세 UI에서 실제 사용하는 조건·유형·교통·시설 카테고리를 모두 제공해야 한다. */
  @Test
  void documents_매물UI의_전체공통코드카테고리를_포함한다() {
    Set<ListingCatalogCategory> categories =
        ListingCatalogSeedFixtures.documents().stream()
            .map(ListingCatalogSeedDocument::getCategory)
            .collect(java.util.stream.Collectors.toSet());

    assertThat(categories).containsExactlyInAnyOrder(ListingCatalogCategory.values());
  }

  /**
   * 조건 chip과 기존 시설의 영어 라벨은 Figma 확정 문구를 그대로 제공해야 한다.
   *
   * <p>이 테스트는 의미가 비슷한 긴 표현으로 문구가 되돌아가는 회귀를 막는다. code는 API·검색 계약이므로 유지하고 화면에 표시되는 영어 label만 정확히
   * 비교한다.
   */
  @Test
  void documents_기존코드의_영어라벨이_Figma문구와_일치한다() {
    Map<String, ListingCatalogSeedDocument> documents = documentsById();

    assertThat(documents.get("CONDITION_TAG:NO_MAINT_FEE").getLabel())
        .containsEntry("ko", "관리비 없음")
        .containsEntry("en", "No Maint. Fee");
    assertThat(documents.get("CONDITION_TAG:NO_ARC").getLabel())
        .containsEntry("ko", "외국인등록증 없이 가능")
        .containsEntry("en", "No ARC");
    assertThat(documents.get("LAUNDRY:DRYER").getLabel())
        .containsEntry("ko", "건조기")
        .containsEntry("en", "Clothes Dryer");
    assertThat(documents.get("PROVIDED_SUPPLY:TISSUE").getLabel())
        .containsEntry("ko", "휴지")
        .containsEntry("en", "Toilet Paper");
  }

  /**
   * 향후 임대인 매물 등록에서 선택할 네 시설은 정본 카탈로그에 미리 존재해야 한다.
   *
   * <p>카탈로그는 번역 사전만 제공하므로 이 테스트가 기존 listing에 시설을 추가하지는 않는다. 실제 상세 노출은 해당 listing의 facilities에 같은
   * code가 저장되었을 때 이루어진다.
   */
  @Test
  void documents_신규시설_네건의_한국어와_영어라벨을_제공한다() {
    Map<String, ListingCatalogSeedDocument> documents = documentsById();

    assertThat(documents.get("LAUNDRY:IRON").getLabel())
        .containsEntry("ko", "다리미")
        .containsEntry("en", "Iron");
    assertThat(documents.get("KITCHEN:ELECTRIC_KETTLE").getLabel())
        .containsEntry("ko", "전기포트")
        .containsEntry("en", "Electric Kettle");
    assertThat(documents.get("COMMON_SPACE:MEETING_ROOM").getLabel())
        .containsEntry("ko", "회의실")
        .containsEntry("en", "Meeting Room");
    assertThat(documents.get("COMMON_SPACE:ROOFTOP").getLabel())
        .containsEntry("ko", "옥상")
        .containsEntry("en", "Rooftop");
  }

  /** 반복되는 category+code 조회를 명확하게 표현하기 위해 전체 정본을 문서 id 기준 맵으로 변환한다. */
  private static Map<String, ListingCatalogSeedDocument> documentsById() {
    return ListingCatalogSeedFixtures.documents().stream()
        .collect(Collectors.toMap(ListingCatalogSeedDocument::getId, document -> document));
  }
}
