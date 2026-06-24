package com.kohere.diagnosis.domain;

import java.util.Optional;

/**
 * 추천 조정 제안 카탈로그 포트(도메인). 구현은 infrastructure의 MongoDB({@code diagnosisSuggestions}) 어댑터가 제공한다. 표시
 * 문자열(번역)을 언어-키 맵으로 보유하며 사유(reason)로 단건 조회한다 — 문항 카탈로그와 동일한 인라인 언어-키 맵 방식(ADR-0029).
 */
public interface SuggestionCatalog {

  /** 사유(reason)로 조정 제안 1건 조회(예: {@code NO_MATCH}). */
  Optional<SuggestionContent> findByReason(String reason);
}
