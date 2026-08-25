package com.kohere.listing.domain;

/**
 * 임대인이 선호하는 입주자 국적이다. 등록 폼 설문 응답이라 세입자 응답에 나가지 않고, 노출 소비처가 없어 {@code listingCatalog}에도 카테고리를 두지
 * 않는다.
 *
 * <p><b>「해당 없음」을 뜻하는 코드는 두지 않는다</b> — 이 필드는 요청에서 선택이라 값이 없으면 빈 집합으로 저장된다(#270). 목록 밖의 국적을 담던 {@code
 * OTHER}도 함께 없앴다: 자유서술 칸이 없어 무엇을 뜻하는지 알 수 없고, 필요해지면 그 지역 코드를 추가하는 편이 낫다.
 */
public enum Nationality {
  JAPAN,
  USA,
  CHINA,
  SOUTHEAST_ASIA,
  EUROPE
}
