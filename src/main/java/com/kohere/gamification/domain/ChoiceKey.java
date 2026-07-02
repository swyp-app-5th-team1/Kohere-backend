package com.kohere.gamification.domain;

/**
 * 4지선다 보기 키(A~D). 언어와 무관하게 불변이며 채점은 이 키로 수행한다(진단 선택지 {@code code}와 동일한 언어-무관 식별 키).
 * docs/api/specs/06-gamification.md.
 */
public enum ChoiceKey {
  A,
  B,
  C,
  D
}
