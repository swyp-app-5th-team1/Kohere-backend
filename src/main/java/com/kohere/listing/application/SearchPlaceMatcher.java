package com.kohere.listing.application;

import com.kohere.listing.domain.SearchPlace;
import com.kohere.listing.domain.SearchPlaceType;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 사용자가 입력한 검색어와 POI 목록을 비교해 가장 적절한 장소 하나를 고른다.
 *
 * <p>MVP에서는 별도 검색 엔진을 두지 않고, 활성 POI를 메모리에서 점수화한다. 점수 기준은 사람이 기대하는 간단한 검색 UX에 맞춰 "정확히 일치 > 별칭 일치 >
 * 앞부분 일치 > 포함 일치" 순서로 둔다.
 */
final class SearchPlaceMatcher {

  private SearchPlaceMatcher() {}

  /** 검색어와 가장 잘 맞는 POI를 하나 반환한다. 어떤 장소에도 걸리지 않으면 empty를 반환한다. */
  static Optional<SearchPlace> bestMatch(String keyword, List<SearchPlace> places) {
    String normalizedKeyword = normalize(keyword);
    if (normalizedKeyword.isBlank()) {
      return Optional.empty();
    }

    return places.stream()
        .map(place -> new MatchCandidate(place, score(place, normalizedKeyword)))
        .filter(candidate -> candidate.score() > 0)
        .max(MatchCandidate.ORDERING)
        .map(MatchCandidate::place);
  }

  /**
   * 장소 하나가 검색어와 얼마나 잘 맞는지 점수화한다.
   *
   * <p>공식 이름의 정확한 일치를 가장 강하게 보고, 그 다음 별칭 정확 일치를 본다. 예를 들어 "연세대"는 연세대학교의 별칭 정확 일치이므로 "서울대입구역"처럼 단순
   * 포함되는 후보보다 앞선다.
   */
  private static int score(SearchPlace place, String keyword) {
    List<String> names = candidateNames(place).map(SearchPlaceMatcher::normalize).toList();
    String officialName = normalize(place.getName());
    int typeWeight = typeWeight(place.getType());

    if (officialName.equals(keyword)) {
      return 400 + typeWeight;
    }
    if (place.getAliases().stream().map(SearchPlaceMatcher::normalize).anyMatch(keyword::equals)) {
      return 390 + typeWeight;
    }
    if (names.stream().anyMatch(name -> name.startsWith(keyword))) {
      return 300 + typeWeight;
    }
    if (names.stream().anyMatch(name -> name.contains(keyword))) {
      return 200 + typeWeight;
    }
    return 0;
  }

  /** 공식 이름과 별칭을 모두 검색 후보 문자열로 사용한다. */
  private static Stream<String> candidateNames(SearchPlace place) {
    return Stream.concat(Stream.of(place.getName()), place.getAliases().stream());
  }

  /**
   * 검색어 비교용 정규화다.
   *
   * <p>대소문자, 앞뒤 공백, 단어 사이 공백·점·하이픈 차이 때문에 검색이 실패하지 않도록 화면 표시 문자열과 별도의 비교 문자열을 만든다.
   */
  private static String normalize(String value) {
    if (value == null) {
      return "";
    }
    return value.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s·.\\-_']", "");
  }

  /** 점수 동률일 때 장소 종류와 seed 우선순위로 안정적인 결과를 고른다. */
  private record MatchCandidate(SearchPlace place, int score) {
    private static final Comparator<MatchCandidate> ORDERING =
        Comparator.comparingInt(MatchCandidate::score)
            .thenComparingInt(candidate -> candidate.place().getPriority())
            .thenComparing(candidate -> candidate.place().getName(), Comparator.reverseOrder());
  }

  /**
   * 대학 검색어는 대학 POI의 별칭/공식명에서 높은 점수를 받는다. 그 밖에 "신촌"처럼 역과 지역이 동시에 걸리는 검색어는 역이 지역보다 위치가 명확하므로 역을 먼저
   * 고른다.
   */
  private static int typeWeight(SearchPlaceType type) {
    return switch (type) {
      case UNIVERSITY -> 30;
      case SUBWAY_STATION -> 20;
      case REGION -> 10;
    };
  }
}
