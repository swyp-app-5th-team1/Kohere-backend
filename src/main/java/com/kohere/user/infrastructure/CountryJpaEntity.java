package com.kohere.user.infrastructure;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 국가 참조 JPA 엔티티(MySQL {@code countries}). 시드/마이그레이션으로 적재되는 reference 데이터로 {@code code}(ISO 3166-1
 * alpha-2)가 PK다. {@code flag}는 국기 이미지 URL(flagcdn.com SVG). {@code lang}은 표시 언어(ISO 639-1)로 다국어 표시의
 * 사용자 언어 결정 출처다(미설정이면 호출 측이 영어로 폴백). database-design §4-2.
 */
@Entity
@Table(name = "countries")
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class CountryJpaEntity {

  @Id private String code;
  private String name;
  private String flag;
  private String lang;
}
