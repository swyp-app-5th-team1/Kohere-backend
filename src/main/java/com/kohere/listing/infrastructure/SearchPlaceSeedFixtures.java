package com.kohere.listing.infrastructure;

import com.kohere.listing.domain.SearchPlaceType;
import java.util.List;
import java.util.Set;

/** MVP 키워드 검색에서 사용할 서울권 POI seed 문서를 만든다. */
final class SearchPlaceSeedFixtures {

  private SearchPlaceSeedFixtures() {}

  /** 대학·주요 지하철역·주요 지역 POI를 한 번에 반환한다. */
  static List<SearchPlaceDocument> documents() {
    return List.of(
        // 대학 POI: 진단 대학 그룹에 노출되는 학교들을 개별 검색 대상으로 둔다.
        place(
            "UNIV_HUFS",
            SearchPlaceType.UNIVERSITY,
            "한국외국어대학교",
            Set.of("한국외대", "외대", "hufs", "hanguk university of foreign studies"),
            37.597319,
            127.058284,
            300),
        place(
            "UNIV_KHU",
            SearchPlaceType.UNIVERSITY,
            "경희대학교",
            Set.of("경희", "경희대", "khu", "kyunghee", "kyung hee", "kyung hee university"),
            37.596195,
            127.052544,
            300),
        place(
            "UNIV_KOREA",
            SearchPlaceType.UNIVERSITY,
            "고려대학교",
            Set.of("고려", "고려대", "korea", "korea university"),
            37.589387,
            127.032477,
            300),
        place(
            "UNIV_SKKU",
            SearchPlaceType.UNIVERSITY,
            "성균관대학교",
            Set.of("성균관", "성균관대", "skku", "sungkyunkwan", "sungkyunkwan university"),
            37.588227,
            126.993606,
            300),
        place(
            "UNIV_SUNGSHIN",
            SearchPlaceType.UNIVERSITY,
            "성신여자대학교",
            Set.of("성신", "성신여대", "sungshin", "sungshin women's university"),
            37.591310,
            127.022131,
            300),
        place(
            "UNIV_SNU",
            SearchPlaceType.UNIVERSITY,
            "서울대학교",
            Set.of("서울대", "snu", "seoul national", "seoul national university"),
            37.459882,
            126.951905,
            300),
        place(
            "UNIV_CAU",
            SearchPlaceType.UNIVERSITY,
            "중앙대학교",
            Set.of("중앙", "중앙대", "cau", "chungang", "chung-ang", "chung-ang university"),
            37.505088,
            126.957101,
            300),
        place(
            "UNIV_SOONGSIL",
            SearchPlaceType.UNIVERSITY,
            "숭실대학교",
            Set.of("숭실", "숭실대", "soongsil", "soongsil university"),
            37.496311,
            126.957459,
            300),
        place(
            "UNIV_HONGIK",
            SearchPlaceType.UNIVERSITY,
            "홍익대학교",
            Set.of("홍익", "홍익대", "홍대", "hongik", "hongik university"),
            37.551464,
            126.925011,
            300),
        place(
            "UNIV_YONSEI",
            SearchPlaceType.UNIVERSITY,
            "연세대학교",
            Set.of("연세", "연세대", "yonsei", "yonsei university"),
            37.565784,
            126.938572,
            300),
        place(
            "UNIV_EWHA",
            SearchPlaceType.UNIVERSITY,
            "이화여자대학교",
            Set.of("이화", "이화여대", "이대", "ewha", "ewha womans university"),
            37.561800,
            126.946900,
            300),
        place(
            "UNIV_KONKUK",
            SearchPlaceType.UNIVERSITY,
            "건국대학교",
            Set.of("건국", "건국대", "konkuk", "konkuk university"),
            37.542650,
            127.077200,
            300),
        place(
            "UNIV_SEJONG",
            SearchPlaceType.UNIVERSITY,
            "세종대학교",
            Set.of("세종", "세종대", "sejong", "sejong university"),
            37.550270,
            127.073000,
            300),
        place(
            "UNIV_HYU",
            SearchPlaceType.UNIVERSITY,
            "한양대학교",
            Set.of("한양", "한양대", "hyu", "hanyang", "hanyang university"),
            37.557232,
            127.045322,
            300),

        // 역 POI: 대학 주변에서 실제 지도 검색어로 자주 입력될 만한 역을 우선 넣는다.
        place(
            "STATION_HUFS",
            SearchPlaceType.SUBWAY_STATION,
            "외대앞역",
            Set.of("외대앞", "외대역", "hankuk university of foreign studies station"),
            37.596073,
            127.063549,
            250),
        place(
            "STATION_HOEGI",
            SearchPlaceType.SUBWAY_STATION,
            "회기역",
            Set.of("회기", "hoegi", "hoegi station"),
            37.589802,
            127.057936,
            250),
        place(
            "STATION_KOREA_UNIV",
            SearchPlaceType.SUBWAY_STATION,
            "고려대역",
            Set.of("고려대", "고려대역", "korea university station"),
            37.590508,
            127.036296,
            250),
        place(
            "STATION_SUNGSHIN",
            SearchPlaceType.SUBWAY_STATION,
            "성신여대입구역",
            Set.of("성신여대입구", "성신여대역", "sungshin women's university station"),
            37.592624,
            127.016403,
            250),
        place(
            "STATION_SNU",
            SearchPlaceType.SUBWAY_STATION,
            "서울대입구역",
            Set.of("서울대입구", "서울대입구역", "seoul national university station"),
            37.481247,
            126.952739,
            250),
        place(
            "STATION_SOONGSIL",
            SearchPlaceType.SUBWAY_STATION,
            "숭실대입구역",
            Set.of("숭실대입구", "숭실대역", "soongsil university station"),
            37.496029,
            126.953822,
            250),
        place(
            "STATION_HONGIK",
            SearchPlaceType.SUBWAY_STATION,
            "홍대입구역",
            Set.of("홍대입구", "홍대역", "hongik university station"),
            37.557192,
            126.925381,
            250),
        place(
            "STATION_SINCHON",
            SearchPlaceType.SUBWAY_STATION,
            "신촌역",
            Set.of("신촌", "sinchon", "sinchon station"),
            37.555153,
            126.936889,
            250),
        place(
            "STATION_EWHA",
            SearchPlaceType.SUBWAY_STATION,
            "이대역",
            Set.of("이대", "ewha station"),
            37.556733,
            126.946013,
            250),
        place(
            "STATION_KONKUK",
            SearchPlaceType.SUBWAY_STATION,
            "건대입구역",
            Set.of("건대입구", "건대역", "konkuk university station"),
            37.540408,
            127.069231,
            250),
        place(
            "STATION_CHILDREN_GRAND_PARK",
            SearchPlaceType.SUBWAY_STATION,
            "어린이대공원역",
            Set.of("어린이대공원", "children's grand park station"),
            37.548014,
            127.074658,
            250),
        place(
            "STATION_HANYANG",
            SearchPlaceType.SUBWAY_STATION,
            "한양대역",
            Set.of("한양대", "hanyang university station"),
            37.555273,
            127.043655,
            250),

        // 지역 POI: 역보다 범위가 넓으므로 같은 매칭 점수에서는 역보다 낮은 우선순위를 갖게 둔다.
        place(
            "REGION_SINCHON",
            SearchPlaceType.REGION,
            "신촌",
            Set.of("신촌동", "sinchon"),
            37.559800,
            126.942400,
            150),
        place(
            "REGION_HONGDAE",
            SearchPlaceType.REGION,
            "홍대",
            Set.of("홍익대상권", "hongdae"),
            37.556300,
            126.923600,
            150),
        place(
            "REGION_EWHA",
            SearchPlaceType.REGION,
            "이대",
            Set.of("이화여대상권", "ewha"),
            37.558500,
            126.945800,
            150),
        place(
            "REGION_KONDAE",
            SearchPlaceType.REGION,
            "건대",
            Set.of("건대입구", "kondae"),
            37.540400,
            127.069200,
            150),
        place(
            "REGION_GWANAK_GU",
            SearchPlaceType.REGION,
            "관악구",
            Set.of("관악", "gwanak", "gwanak-gu"),
            37.478400,
            126.951600,
            150),
        place(
            "REGION_MAPO_GU",
            SearchPlaceType.REGION,
            "마포구",
            Set.of("마포", "mapo", "mapo-gu"),
            37.566300,
            126.901900,
            150),
        place(
            "REGION_DONGDAEMUN_GU",
            SearchPlaceType.REGION,
            "동대문구",
            Set.of("동대문", "dongdaemun", "dongdaemun-gu"),
            37.574400,
            127.039500,
            150),
        place(
            "REGION_SEONGBUK_GU",
            SearchPlaceType.REGION,
            "성북구",
            Set.of("성북", "seongbuk", "seongbuk-gu"),
            37.589400,
            127.016700,
            150),
        place(
            "REGION_GWANGJIN_GU",
            SearchPlaceType.REGION,
            "광진구",
            Set.of("광진", "gwangjin", "gwangjin-gu"),
            37.538400,
            127.082300,
            150),
        place(
            "REGION_SEODAEMUN_GU",
            SearchPlaceType.REGION,
            "서대문구",
            Set.of("서대문", "seodaemun", "seodaemun-gu"),
            37.579100,
            126.936800,
            150),
        place(
            "REGION_DONGJAK_GU",
            SearchPlaceType.REGION,
            "동작구",
            Set.of("동작", "dongjak", "dongjak-gu"),
            37.512400,
            126.939300,
            150),
        place(
            "REGION_SEONGDONG_GU",
            SearchPlaceType.REGION,
            "성동구",
            Set.of("성동", "seongdong", "seongdong-gu"),
            37.563300,
            127.037100,
            150));
  }

  private static SearchPlaceDocument place(
      String id,
      SearchPlaceType type,
      String name,
      Set<String> aliases,
      double lat,
      double lng,
      int priority) {
    return SearchPlaceDocument.builder()
        .id(id)
        .type(type)
        .name(name)
        .aliases(aliases)
        .lat(lat)
        .lng(lng)
        .active(true)
        .priority(priority)
        .build();
  }
}
