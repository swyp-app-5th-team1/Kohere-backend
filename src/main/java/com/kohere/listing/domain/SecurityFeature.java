package com.kohere.listing.domain;

/** 보안·안전 시설이다. */
public enum SecurityFeature {
  CCTV,
  ENTRANCE_DOOR_LOCK,
  DOOR_LOCK,
  FIRE_EXTINGUISHER,
  FIRE_ALARM,
  SECURITY_GUARD,

  /** 해당 시설이 없다. <b>단독으로만</b> 보낼 수 있다 — 다른 코드와 함께 오면 400이다. */
  NONE
}
