package com.kohere.listing.domain.place;

import java.util.List;

/**
 * 검색 장소 사전을 읽는 도메인 포트다.
 *
 * <p>application 계층은 "활성 POI 목록이 필요하다"는 의도만 알고, MongoDB 컬렉션 이름이나 쿼리 방식은 infrastructure 구현에 숨긴다.
 */
public interface SearchPlaceRepository {

  /** 키워드 매칭 후보로 사용할 활성 장소 목록을 우선순위 높은 순으로 반환한다. */
  List<SearchPlace> findActive();
}
