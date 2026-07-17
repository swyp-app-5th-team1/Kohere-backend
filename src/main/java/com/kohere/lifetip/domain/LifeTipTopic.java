package com.kohere.lifetip.domain;

import java.util.Map;

/**
 * 생활 팁 주제(도메인 값). 표시명({@code name})·짧은/긴 설명({@code shortDescription}·{@code longDescription})은 언어
 * 코드를 키로 하는 인라인 맵으로 보유하며, 응용 계층이 사용자 언어로 골라 표시 DTO를 조립한다(ADR-0029, US-8-3). 식별 {@code code}와 이미지
 * URL({@code imageUrl}·{@code backgroundImageUrl})은 언어 무관 불변이다.
 *
 * @param code 주제 코드(UPPER_SNAKE, 언어 무관 불변 식별자)
 * @param name 언어-키 맵 표시명(예: {@code {"en": "...", "ko": "..."}})
 * @param shortDescription 언어-키 맵 짧은 설명(홈 카드용)
 * @param longDescription 언어-키 맵 긴 설명(주제 상세 상단용)
 * @param imageUrl 홈 카드 이미지(언어 무관 절대 CDN URL)
 * @param backgroundImageUrl 주제 상세 상단 배경 이미지(언어 무관 절대 CDN URL)
 * @param order 노출 순서(오름차순)
 */
public record LifeTipTopic(
    String code,
    Map<String, String> name,
    Map<String, String> shortDescription,
    Map<String, String> longDescription,
    String imageUrl,
    String backgroundImageUrl,
    int order) {}
