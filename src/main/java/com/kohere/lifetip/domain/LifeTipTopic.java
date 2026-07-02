package com.kohere.lifetip.domain;

import java.util.Map;

/**
 * 생활 팁 주제(도메인 값). 표시명(name)은 언어 코드를 키로 하는 인라인 맵으로 보유하며, 응용 계층이 사용자 언어로 골라 표시 DTO를 조립한다(ADR-0029,
 * US-8-3). 식별 {@code code}는 언어 무관 불변(UPPER_SNAKE)이다.
 *
 * @param code 주제 코드(UPPER_SNAKE, 언어 무관 불변 식별자)
 * @param name 언어-키 맵 표시명(예: {@code {"en": "...", "ko": "..."}})
 * @param order 노출 순서(오름차순)
 */
public record LifeTipTopic(String code, Map<String, String> name, int order) {}
