package com.kohere.listing.domain;

/** 임대인이 제공하는 물품이다. */
public enum ProvidedSupply {
  BEDDING,
  LAUNDRY_DETERGENT,
  SEASONING,
  SLIPPERS,
  TISSUE,
  TOWEL,

  /** 해당 시설이 없다. <b>단독으로만</b> 보낼 수 있다 — 다른 코드와 함께 오면 400이다. */
  NONE
}
