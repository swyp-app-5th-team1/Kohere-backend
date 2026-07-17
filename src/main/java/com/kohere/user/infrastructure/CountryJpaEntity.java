package com.kohere.user.infrastructure;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 국가 참조 JPA 엔티티(MySQL {@code countries}). 시드/마이그레이션으로 적재되는 reference 데이터로 {@code code}(ISO 3166-1
 * alpha-2)가 PK다. {@code flag}는 국기 이미지 URL(flagcdn.com SVG). 표시 언어는 국적과 무관한 {@code users.lang}로 정하므로
 * 국가 참조에는 언어 컬럼이 없다(ADR-0029 개정, #141 — V13에서 {@code countries.lang} 제거). database-design §4-2.
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
}
