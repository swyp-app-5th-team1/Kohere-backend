package com.kohere.gamification.domain;

import java.util.Map;

/**
 * 4지선다 보기 한 개(도메인 값). {@code key}는 언어 무관 보기 키(A~D), {@code text}는 언어-키 맵 표시 텍스트다(ADR-0029).
 * docs/api/specs/06-gamification.md.
 */
public record QuizChoice(ChoiceKey key, Map<String, String> text) {}
