package com.kohere.listing.domain;

/** 공용 주방에 갖춰진 시설이다. */
public enum KitchenFacility {
  SHARED_REFRIGERATOR,
  INDUCTION,
  GAS_STOVE,
  MICROWAVE,
  ELECTRIC_KETTLE,
  RICE_COOKER,
  TOASTER,
  COFFEE_MACHINE,
  WATER_PURIFIER,

  /** 해당 시설이 없다. <b>단독으로만</b> 보낼 수 있다 — 다른 코드와 함께 오면 400이다. */
  NONE
}
