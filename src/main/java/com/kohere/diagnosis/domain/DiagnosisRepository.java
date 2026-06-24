package com.kohere.diagnosis.domain;

import java.util.List;
import java.util.Optional;

/**
 * 진단 영속 포트(도메인). 구현은 infrastructure의 MongoDB 어댑터가 제공한다(의존성 역전, docs/convention/code-style.md §3-3).
 *
 * <p>이력·최근 진단 조회는 COMPLETED만 노출하며, 진행 중(IN_PROGRESS) 초안은 사용자당 1건만 존재한다.
 */
public interface DiagnosisRepository {

  /** 진단 저장(신규는 id 부여, 기존은 갱신). */
  Diagnosis save(Diagnosis diagnosis);

  /** id로 단건 조회(소유권 검증은 응용 계층). */
  Optional<Diagnosis> findById(Long id);

  /** 사용자의 진행 중(IN_PROGRESS) 진단 초안 조회(단계별 답 저장 대상). */
  Optional<Diagnosis> findInProgressByUserId(Long userId);

  /** 사용자의 최근 완료(COMPLETED) 진단 단건(submittedAt 내림차순 최상위). */
  Optional<Diagnosis> findLatestCompletedByUserId(Long userId);

  /** 사용자의 완료(COMPLETED) 진단 이력(submittedAt 정렬, 오프셋 페이지). {@code ascending=false}면 내림차순. */
  List<Diagnosis> findCompletedByUserId(Long userId, int page, int size, boolean ascending);

  /** 사용자의 완료(COMPLETED) 진단 총 개수(페이지 메타용). */
  long countCompletedByUserId(Long userId);
}
