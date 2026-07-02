package com.kohere.lifetip.domain;

import java.util.Map;

/**
 * 생활 팁 항목(도메인 값). 표시 문자열(title·content)은 언어-키 맵으로 보유하며 응용 계층이 사용자 언어로 조립한다(ADR-0029, US-8-3). 하나의
 * 주제({@code topicCode})에 속한다(주제 : 팁 = 1 : N). 식별 {@code id}·사진 {@code imageUrl}은 언어 무관 불변이다.
 *
 * @param id 팁 식별자(언어 무관 불변)
 * @param topicCode 소속 주제 코드(→ {@link LifeTipTopic#code()})
 * @param title 언어-키 맵 제목
 * @param content 언어-키 맵 내용
 * @param imageUrl 사진 URL(언어 무관, 없으면 {@code null})
 * @param order 주제 내 노출 순서(오름차순)
 */
public record LifeTip(
    String id,
    String topicCode,
    Map<String, String> title,
    Map<String, String> content,
    String imageUrl,
    int order) {}
