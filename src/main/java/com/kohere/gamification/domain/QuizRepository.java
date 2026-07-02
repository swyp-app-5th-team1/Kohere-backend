package com.kohere.gamification.domain;

import java.util.Optional;

/**
 * 퀴즈 카탈로그 영속 포트(도메인). 구현은 infrastructure의 MongoDB({@code quizzes}) 어댑터가 제공한다(ADR-0005·ADR-0035).
 * 무상태 — 제출 기록·포인트를 다루지 않는다.
 */
public interface QuizRepository {

  /** 활성 퀴즈 풀에서 무작위로 1개를 선택한다(랜덤 조회). 풀이 비어 있으면 빈 값. */
  Optional<Quiz> findRandomActive();

  /** 채점 대상 퀴즈를 ID로 조회한다. 없으면 빈 값. */
  Optional<Quiz> findById(Long id);
}
