package com.kohere.listing.domain;

/**
 * 임대인이 외국인 임차인과 계약할 때 겪은 어려움이다. 등록 폼 설문 응답이라 세입자 응답에 나가지 않고, {@code listingCatalog}에도 카테고리를 두지 않는다.
 *
 * <p>「해당 없음」 코드를 두지 않는 이유는 {@link Nationality}와 같다 — 요청에서 선택이라 값이 없으면 빈 집합이고, 뜻을 적을 칸이 없는 {@code
 * OTHER}는 없앴다(#270).
 */
public enum ContractDifficulty {
  LANGUAGE,
  CULTURE,
  IDENTITY,
  PAYMENT,
  CONTRACT_FULFILLMENT,
  COMMUNICATION_CHANNEL
}
