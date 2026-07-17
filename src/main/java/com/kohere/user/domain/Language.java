package com.kohere.user.domain;

import java.util.Locale;
import java.util.Optional;

/**
 * 사용자가 고를 수 있는 표시 언어(ISO 639-1). 카탈로그(진단·퀴즈·생활 팁)에 콘텐츠가 시드된 언어만 값으로 가지며, 목록 밖 코드는 응용 계층이 파싱 단계에서
 * {@code INVALID_INPUT}으로 거부한다(#141, ADR-0029 개정). 닫힌 집합이라 {@code Gender}·{@code Occupation}·{@code
 * VisaType}과 동일하게 enum으로 둔다 — 신규 언어 추가는 카탈로그 콘텐츠 시드가 선행되어 어차피 배포를 수반한다.
 *
 * <p>DB·API·카탈로그 키는 모두 소문자 코드({@link #code()})로 통일한다(저장은 {@code LanguageConverter}). 모듈 경계(공개 query
 * {@code getLanguage})는 이 enum이 아니라 {@code code()} 문자열을 반환한다(user enum 비공유, ADR-0002).
 */
public enum Language {
  EN,
  KO,
  JA;

  /** ISO 639-1 소문자 코드(예 {@code EN}→{@code "en"}). 카탈로그 언어-키·API·DB 표현이다. */
  public String code() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** 소문자 코드로 지원 언어를 찾는다. {@code null}·미지원 코드는 빈 값이다(대문자·지역 태그 불일치 포함). */
  public static Optional<Language> from(String code) {
    if (code == null) {
      return Optional.empty();
    }
    for (Language language : values()) {
      if (language.code().equals(code)) {
        return Optional.of(language);
      }
    }
    return Optional.empty();
  }
}
