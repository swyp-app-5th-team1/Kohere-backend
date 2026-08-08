package com.kohere.listing.infrastructure.external.naver;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * 네이버 지역 검색 JSON 중 Kohere가 실제로 사용하는 필드만 역직렬화하는 인프라 DTO다.
 *
 * <p>{@code lastBuildDate}, {@code total}, {@code link}, {@code category}처럼 프론트 계약에 필요 없는 필드는 무시한다.
 * 이 타입은 네이버 스키마가 응용·도메인 계층으로 새어 나가지 않도록 인프라 패키지 안에만 둔다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record NaverLocalSearchResponse(List<Item> items) {

  /** 네이버 장소 한 건의 원본 필드다. 좌표는 API가 정수 형태로 내려주지만 JSON 호환성과 정밀도 보존을 위해 문자열로 받은 뒤 명시적으로 변환한다. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record Item(String title, String address, String roadAddress, String mapx, String mapy) {}
}
