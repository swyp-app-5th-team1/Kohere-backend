package com.kohere.user.domain;

/**
 * 국가 참조 값. 국적은 {@code code}(ISO 3166-1 alpha-2)로 식별하고, 표시명({@code name})·국기({@code flag})는 {@code
 * countries} 참조 데이터에서 확보한다(클라이언트는 국가 코드만 전송). {@code flag}는 국기 이미지 URL(flagcdn.com SVG)이다. 표시 언어는
 * 국적과 무관한 별도 {@code users.lang} 속성으로 정하므로 국가 참조에는 언어가 없다(ADR-0029 개정, #141). database-design §4-2 ·
 * domain-model §2(국가 참조).
 */
public record Country(String code, String name, String flag) {}
