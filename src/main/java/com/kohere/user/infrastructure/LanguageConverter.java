package com.kohere.user.infrastructure;

import com.kohere.user.domain.Language;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * {@link Language} ↔ DB 표현 변환기. DB엔 enum 상수명(EN)이 아니라 ISO 639-1 소문자 코드({@code en}/{@code ko}/{@code
 * ja})를 저장해 카탈로그 언어-키·API 표현과 통일한다(VisaTypeConverter와 동일 취지, #141). 미지원·NULL 값은 {@code null}로 읽는다 —
 * 쓰기 시 응용 계층이 지원 목록을 강제하므로 DB엔 지원 코드만 남는다.
 */
@Converter
public class LanguageConverter implements AttributeConverter<Language, String> {

  @Override
  public String convertToDatabaseColumn(Language language) {
    return language == null ? null : language.code();
  }

  @Override
  public Language convertToEntityAttribute(String dbValue) {
    return Language.from(dbValue).orElse(null);
  }
}
