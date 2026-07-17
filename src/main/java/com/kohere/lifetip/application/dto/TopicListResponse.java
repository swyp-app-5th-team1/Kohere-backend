package com.kohere.lifetip.application.dto;

import java.util.List;

/**
 * 생활 팁 주제 목록 응답 DTO. 표시명({@code name})·짧은/긴 설명({@code shortDescription}·{@code longDescription})은
 * 등록 국가 언어로 번역되고, {@code code}와 이미지 URL({@code imageUrl}·{@code backgroundImageUrl})은 언어 무관
 * 동일하다(US-8-1·US-8-3). 고정·소규모 카탈로그라 페이지네이션 없이 전체 배열을 담는다.
 *
 * @param topics 노출 순서대로의 주제 목록
 */
public record TopicListResponse(List<Topic> topics) {

  /**
   * 주제 1건. 홈 카드는 {@code imageUrl}+{@code shortDescription}, 주제 상세 상단은 {@code
   * backgroundImageUrl}+{@code longDescription}을 쓴다.
   *
   * @param code 언어 무관 주제 코드(UPPER_SNAKE)
   * @param name 등록 국가 언어로 번역된 표시명
   * @param shortDescription 등록 국가 언어로 번역된 짧은 설명(홈 카드용)
   * @param longDescription 등록 국가 언어로 번역된 긴 설명(주제 상세 상단용)
   * @param imageUrl 홈 카드 이미지 URL(언어 무관)
   * @param backgroundImageUrl 주제 상세 상단 배경 이미지 URL(언어 무관)
   */
  public record Topic(
      String code,
      String name,
      String shortDescription,
      String longDescription,
      String imageUrl,
      String backgroundImageUrl) {}
}
