package com.kohere.listing.domain;

/** 세탁 관련 시설이다. */
public enum LaundryFacility {
  WASHER,
  DRYER,
  DRYING_RACK,
  IRON,

  /** 해당 시설이 없다. <b>단독으로만</b> 보낼 수 있다 — 다른 코드와 함께 오면 400이다. */
  NONE
}
