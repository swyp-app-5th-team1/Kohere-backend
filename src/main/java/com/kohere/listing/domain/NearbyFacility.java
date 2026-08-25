package com.kohere.listing.domain;

/** 매물 주변 편의시설이다. 교통과 무관한 값이라 Listing 루트가 소유한다. */
public enum NearbyFacility {
  CONVENIENCE_STORE,
  MART,
  HOSPITAL_PHARMACY,
  PARK,
  LAUNDROMAT,

  /** 해당 시설이 없다. <b>단독으로만</b> 보낼 수 있다 — 다른 코드와 함께 오면 400이다. */
  NONE
}
