package com.kohere.listing.domain;

import java.util.List;

/**
 * 외부 장소 검색 제공자에 의존하지 않는 장소 검색 포트다.
 *
 * <p>응용 계층은 네이버의 URL, 인증 헤더, 원본 응답 스키마를 알지 않고 이 계약만 호출한다. 외부 검색 제공자를 교체하거나 테스트에서 가짜 구현을 주입하더라도 장소
 * 검색 유스케이스가 영향을 받지 않도록 인프라 의존 방향을 역전한다.
 */
public interface PlaceSearchClient {

  /**
   * 정규화와 입력 검증이 끝난 키워드로 표시 가능한 장소 후보를 검색한다.
   *
   * @param keyword 앞뒤 공백이 제거된 유효한 검색어
   * @return 제공자의 검색 순서를 유지한 장소 후보 목록. 결과가 없으면 빈 목록
   * @throws PlaceSearchUpstreamException 외부 제공자를 호출하지 못했거나 응답 계약을 해석할 수 없는 경우
   */
  List<PlaceSearchResult> search(String keyword);
}
