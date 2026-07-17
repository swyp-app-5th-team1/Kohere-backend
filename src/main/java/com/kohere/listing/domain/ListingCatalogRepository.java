package com.kohere.listing.domain;

import java.util.List;

/**
 * Listing 공통 코드 번역 카탈로그를 읽는 도메인 포트다.
 *
 * <p>응답 한 건마다 코드를 개별 조회하면 목록 크기만큼 MongoDB 호출이 늘어난다. 그래서 한 요청에서 전체 카탈로그를 한 번 읽고 메모리 맵으로 변환해 모든 매물
 * 응답에 재사용한다.
 */
public interface ListingCatalogRepository {

  /** 현재 저장된 전체 Listing 표시 코드와 번역을 반환한다. */
  List<ListingCatalogEntry> findAll();
}
