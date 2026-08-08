package com.kohere.listing.domain.place;

/** 키워드 검색에서 사용자가 입력한 단어가 어떤 종류의 장소와 매칭되었는지 나타낸다. */
public enum SearchPlaceType {
  /** 대학교. 예: 연세대학교, 서울대학교, 한국외국어대학교. */
  UNIVERSITY,

  /** 지하철역. 예: 신촌역, 서울대입구역, 건대입구역. */
  SUBWAY_STATION,

  /** 지역·상권·행정구역. 예: 신촌, 홍대, 관악구. */
  REGION
}
