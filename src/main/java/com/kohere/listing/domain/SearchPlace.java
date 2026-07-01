package com.kohere.listing.domain;

import com.kohere.common.exception.InvalidInputException;
import java.util.Set;
import lombok.Builder;
import lombok.Getter;

/**
 * 키워드 검색의 기준이 되는 장소(POI, Point Of Interest)다.
 *
 * <p>사용자가 "연세", "신촌", "서울대입구"처럼 지도에서 찾고 싶은 단어를 입력하면 서버는 이 장소 목록의 {@code name}과 {@code aliases}를
 * 비교한다. 매칭된 장소의 좌표를 기준으로 주변 매물을 조회하므로, 이 객체는 "검색어를 지도 좌표로 바꾸는 사전" 역할을 한다.
 */
@Getter
@Builder
public class SearchPlace {

  /** seed와 운영 데이터에서 안정적으로 재사용할 장소 식별자다. 예: {@code UNIV_YONSEI}. */
  private final String id;

  /** 프론트가 장소 뱃지나 안내 문구를 구분할 때 쓰는 장소 종류다. */
  private final SearchPlaceType type;

  /** 사용자에게 보여줄 공식 장소명이다. 예: {@code 연세대학교}, {@code 신촌역}. */
  private final String name;

  /**
   * 공식 이름 외에 사용자가 검색할 만한 별명 목록이다.
   *
   * <p>예를 들어 공식 이름이 {@code 연세대학교}여도 사용자는 {@code 연세}, {@code 연세대}, {@code yonsei}처럼 입력할 수 있다.
   */
  private final Set<String> aliases;

  /** 지도 중심 이동과 주변 매물 조회에 쓰는 WGS84 위도다. */
  private final double lat;

  /** 지도 중심 이동과 주변 매물 조회에 쓰는 WGS84 경도다. */
  private final double lng;

  /** 운영 중 일시적으로 검색에서 제외할 수 있게 둔 플래그다. */
  private final boolean active;

  /** 같은 점수로 여러 장소가 매칭될 때 더 대표적인 장소를 먼저 고르기 위한 값이다. 클수록 우선된다. */
  private final int priority;

  /** 장소 좌표와 필수 표시값이 비어 있지 않은지 방어적으로 확인한다. */
  public SearchPlace(
      String id,
      SearchPlaceType type,
      String name,
      Set<String> aliases,
      double lat,
      double lng,
      boolean active,
      int priority) {
    if (id == null || id.isBlank()) {
      throw new InvalidInputException("searchPlace.id는 필수입니다.");
    }
    if (type == null) {
      throw new InvalidInputException("searchPlace.type은 필수입니다.");
    }
    if (name == null || name.isBlank()) {
      throw new InvalidInputException("searchPlace.name은 필수입니다.");
    }
    if (lat < -90.0 || lat > 90.0 || lng < -180.0 || lng > 180.0) {
      throw new InvalidInputException("searchPlace 좌표가 올바르지 않습니다.");
    }
    this.id = id;
    this.type = type;
    this.name = name;
    this.aliases = aliases == null ? Set.of() : Set.copyOf(aliases);
    this.lat = lat;
    this.lng = lng;
    this.active = active;
    this.priority = priority;
  }
}
