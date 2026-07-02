package com.kohere.lifetip.domain;

import java.util.List;

/**
 * 생활 팁 카탈로그 포트(도메인). 구현은 infrastructure의 MongoDB({@code lifeTipTopics}·{@code lifeTips}) 어댑터가 제공한다.
 * 운영이 시드로 적재하는 읽기 전용 큐레이션 카탈로그다(US-8). 표시 문자열은 언어-키 맵으로 보유하며 응용 계층이 사용자 언어로 번역한다(ADR-0029).
 */
public interface LifeTipCatalog {

  /** 주제 전체를 노출 순서({@code order} 오름차순)로 조회한다. */
  List<LifeTipTopic> findAllTopics();

  /** 주제 코드 존재 여부. */
  boolean topicExists(String topicCode);

  /** 주제({@code topicCode})에 속한 팁 전체를 노출 순서({@code order} 오름차순)로 조회한다. */
  List<LifeTip> findTipsByTopicCode(String topicCode);
}
