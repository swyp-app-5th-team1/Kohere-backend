package com.kohere.listing.presentation.dto;

import com.kohere.listing.domain.ListingSort;
import lombok.Getter;
import lombok.Setter;

/**
 * 키워드 검색 API의 쿼리 파라미터를 담는다.
 *
 * <p>사용자는 학교명·지역명·지하철역명을 {@code keyword} 하나로 보낸다. 서버는 이 값을 POI 사전에서 장소로 매칭한 뒤, 그 장소 주변 매물을 페이지로
 * 반환한다.
 */
@Getter
@Setter
public class ListingKeywordSearchRequest {

  /** 검색어. 예: "연세", "신촌", "서울대입구". 서비스 계층에서 필수·공백·길이를 검증한다. */
  private String keyword;

  /** 정렬 프리셋. 키워드 검색은 장소 좌표가 있으므로 기본값을 가까운 순(DISTANCE)으로 둔다. */
  private ListingSort sort = ListingSort.DISTANCE;

  /** 0부터 시작하는 페이지 번호다. */
  private int page = 0;

  /** 페이지 크기다. API 공통 정책에 따라 서비스 계층에서 1~100 범위를 검증한다. */
  private int size = 20;
}
