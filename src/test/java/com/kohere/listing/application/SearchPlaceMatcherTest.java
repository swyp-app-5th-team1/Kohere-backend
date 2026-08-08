package com.kohere.listing.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.kohere.listing.domain.place.SearchPlace;
import com.kohere.listing.domain.place.SearchPlaceType;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 키워드 검색 POI 매칭 규칙을 작은 단위로 검증한다. */
class SearchPlaceMatcherTest {

  /** 공식 이름이 정확히 들어오면 해당 장소가 바로 선택된다. */
  @Test
  void exactName_공식이름이_정확히_일치하면_선택한다() {
    SearchPlace result = SearchPlaceMatcher.bestMatch("연세대학교", places()).orElseThrow();

    assertThat(result.getName()).isEqualTo("연세대학교");
    assertThat(result.getType()).isEqualTo(SearchPlaceType.UNIVERSITY);
  }

  /** 사용자가 공식 이름 대신 별칭을 입력해도 같은 장소로 매칭한다. */
  @Test
  void exactAlias_별칭이_정확히_일치하면_선택한다() {
    SearchPlace result = SearchPlaceMatcher.bestMatch("연세대", places()).orElseThrow();

    assertThat(result.getName()).isEqualTo("연세대학교");
  }

  /** 일부만 입력해도 앞부분/포함 일치로 매칭한다. */
  @Test
  void partialKeyword_일부만_입력해도_매칭한다() {
    SearchPlace result = SearchPlaceMatcher.bestMatch("연세", places()).orElseThrow();

    assertThat(result.getName()).isEqualTo("연세대학교");
  }

  /** 영문 검색어는 대소문자와 공백 차이를 무시하고 비교한다. */
  @Test
  void englishAlias_대소문자와_공백을_무시한다() {
    SearchPlace result =
        SearchPlaceMatcher.bestMatch(" Yonsei University ", places()).orElseThrow();

    assertThat(result.getName()).isEqualTo("연세대학교");
  }

  /** 신촌처럼 역과 지역이 동시에 걸리는 검색어는 위치가 더 명확한 역을 우선한다. */
  @Test
  void ambiguousStationAndRegion_역을_지역보다_우선한다() {
    SearchPlace result = SearchPlaceMatcher.bestMatch("신촌", places()).orElseThrow();

    assertThat(result.getName()).isEqualTo("신촌역");
    assertThat(result.getType()).isEqualTo(SearchPlaceType.SUBWAY_STATION);
  }

  /** 서울대처럼 대학과 역 이름이 모두 떠오르는 검색어는 대학 별칭 정확 일치가 역 앞부분 일치보다 앞선다. */
  @Test
  void universityAlias_대학별칭은_역_부분일치보다_우선한다() {
    SearchPlace result = SearchPlaceMatcher.bestMatch("서울대", places()).orElseThrow();

    assertThat(result.getName()).isEqualTo("서울대학교");
  }

  /** POI 사전에 없는 검색어는 빈 Optional을 반환한다. */
  @Test
  void noMatch_매칭이_없으면_empty를_반환한다() {
    assertThat(SearchPlaceMatcher.bestMatch("없는장소", places())).isEmpty();
  }

  private static List<SearchPlace> places() {
    return List.of(
        place(
            "UNIV_YONSEI",
            SearchPlaceType.UNIVERSITY,
            "연세대학교",
            Set.of("연세", "연세대", "yonsei", "yonsei university"),
            300),
        place(
            "UNIV_SNU",
            SearchPlaceType.UNIVERSITY,
            "서울대학교",
            Set.of("서울대", "snu", "seoul national university"),
            300),
        place(
            "STATION_SNU",
            SearchPlaceType.SUBWAY_STATION,
            "서울대입구역",
            Set.of("서울대입구", "seoul national university station"),
            250),
        place(
            "STATION_SINCHON", SearchPlaceType.SUBWAY_STATION, "신촌역", Set.of("신촌", "sinchon"), 250),
        place("REGION_SINCHON", SearchPlaceType.REGION, "신촌", Set.of("신촌동", "sinchon"), 150));
  }

  private static SearchPlace place(
      String id, SearchPlaceType type, String name, Set<String> aliases, int priority) {
    return SearchPlace.builder()
        .id(id)
        .type(type)
        .name(name)
        .aliases(aliases)
        .lat(37.0)
        .lng(127.0)
        .active(true)
        .priority(priority)
        .build();
  }
}
