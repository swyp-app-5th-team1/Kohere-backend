package com.kohere.listing.domain;

/**
 * 임대인이 응대 가능한 외국어다. 복수 선택한다.
 *
 * <p>목록 밖의 언어를 담던 {@code OTHER}는 없앴다(#270) — 어떤 언어인지 적을 칸이 없어 세입자에게도 운영에도 쓸 수 없는 값이었다. 필요해지면 그 언어
 * 코드를 카탈로그와 함께 추가한다.
 */
public enum SupportedLanguage {
  ENGLISH,
  CHINESE,
  JAPANESE
}
