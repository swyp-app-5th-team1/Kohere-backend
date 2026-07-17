package com.kohere.listing.domain;

import com.kohere.common.exception.InvalidInputException;

/**
 * Listing 도메인에서 사용하는 한국어·영어 문구 값 객체다.
 *
 * <p>MongoDB에는 {@code {"ko": "...", "en": "..."}} 모양으로 저장하고, API 응답을 만들 때 사용자의 표시 언어에 해당하는 문자열 하나만
 * 선택한다. 현재 MVP의 기본 언어는 영어이므로 요청 언어가 없거나 지원되지 않으면 영어를 반환한다.
 *
 * @param ko 한국어 문구
 * @param en 영어 문구이자 지원하지 않는 언어의 기본 문구
 */
public record LocalizedText(String ko, String en) {

  /** MVP에서 번역 누락 시 사용하는 기본 언어다. */
  public static final String DEFAULT_LANGUAGE = "en";

  /**
   * 두 언어가 모두 비어 있지 않은지 생성 시점에 검증한다.
   *
   * <p>번역이 누락된 문서를 저장 단계에서 차단해야 외국인 사용자의 화면에 빈 제목이나 빈 주소가 노출되지 않는다.
   */
  public LocalizedText {
    requireText(ko, "한국어(ko) 문구가 필요합니다.");
    requireText(en, "영어(en) 문구가 필요합니다.");
  }

  /**
   * 사용자 언어에 맞는 문구를 반환한다.
   *
   * <p>현재 Listing MVP는 한국어와 영어만 저장한다. {@code language=ko}일 때만 한국어를 사용하고, 영어·null·그 밖의 언어는 모두 영어로
   * 폴백한다. 이 정책은 기존 진단 다국어 처리의 영어 폴백 규칙과 같다.
   */
  public String resolve(String language) {
    return "ko".equalsIgnoreCase(language) ? ko : en;
  }

  /** 기존 단일 언어 테스트 데이터나 내부 호환 값에 같은 문구를 두 언어로 채우는 편의 팩토리다. */
  public static LocalizedText same(String value) {
    return new LocalizedText(value, value);
  }

  /** 필수 문구가 null 또는 공백인지 검사한다. */
  private static void requireText(String value, String message) {
    if (value == null || value.isBlank()) {
      throw new InvalidInputException(message);
    }
  }
}
