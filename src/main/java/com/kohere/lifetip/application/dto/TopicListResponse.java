package com.kohere.lifetip.application.dto;

import java.util.List;

/**
 * 생활 팁 주제 목록 응답 DTO. 등록 국가 언어로 번역된 표시명({@code name})만 담고 {@code code}는 언어 무관 동일하다(US-8-1·US-8-3).
 * 고정·소규모 카탈로그라 페이지네이션 없이 전체 배열을 담는다.
 *
 * @param topics 노출 순서대로의 주제 목록
 */
public record TopicListResponse(List<Topic> topics) {

  /**
   * 주제 1건.
   *
   * @param code 언어 무관 주제 코드(UPPER_SNAKE)
   * @param name 등록 국가 언어로 번역된 표시명
   */
  public record Topic(String code, String name) {}
}
