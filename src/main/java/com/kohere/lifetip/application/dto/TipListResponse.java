package com.kohere.lifetip.application.dto;

import java.util.List;

/**
 * 특정 주제의 생활 팁 목록 응답 DTO. 등록 국가 언어로 번역된 제목·내용과 언어 무관 사진 URL을 담는다(US-8-2·US-8-3). 주제당 팁 수가 제한적이라
 * 페이지네이션 없이 전체 배열을 담는다.
 *
 * @param tips 노출 순서대로의 팁 목록
 */
public record TipListResponse(List<Tip> tips) {

  /**
   * 생활 팁 1건.
   *
   * @param id 언어 무관 팁 식별자
   * @param title 번역된 제목
   * @param content 번역된 내용
   * @param imageUrl 사진 URL(언어 무관, 없으면 {@code null})
   */
  public record Tip(String id, String title, String content, String imageUrl) {}
}
