package com.kohere.listing.domain;

/** UI 필터 칩과 방 상품 태그에 쓰는 주거 조건이다. NO_ARC는 매물 정책으로 계산하는 가상 필터다. */
public enum ConditionTag {
  MOVE_IN_NOW,
  FEMALE_ONLY,
  MEALS_INCLUDED,
  DOUBLE_ROOM,
  PRIVATE_BATH,
  ENGLISH_OK,
  ADDRESS_REGISTRATION,
  NO_MAINT_FEE,
  NO_ARC;

  /** MongoDB roomOffers.filterTags에 실제 저장되는 방 상품 태그인지 알려준다. */
  public boolean storedInRoomOfferFilterTags() {
    return this != NO_ARC;
  }
}
