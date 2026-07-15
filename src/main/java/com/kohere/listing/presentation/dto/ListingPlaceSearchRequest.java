package com.kohere.listing.presentation.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 네이버 장소 후보 검색 API의 쿼리 파라미터를 담는다.
 *
 * <p>프론트는 검색어만 전달하고, 네이버의 {@code display}, {@code start}, {@code sort} 같은 제공자 전용 옵션은 서버 정책으로 고정한다.
 * 검색어의 공백 제거와 길이 검증은 응용 계층에서 일관되게 수행한다.
 */
@Getter
@Setter
public class ListingPlaceSearchRequest {

  /** 사용자가 지도 검색창에 입력한 원본 검색어. 예: {@code 경희대}. */
  private String keyword;
}
