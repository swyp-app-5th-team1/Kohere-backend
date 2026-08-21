package com.kohere.report.domain;

/** 신고 당시 최근 TEXT 원문 스냅샷을 저장하는 도메인 포트다. */
public interface ReportEvidenceRepository {

  /** 신고 기본 행과 같은 트랜잭션에서 증거 한 건을 저장한다. */
  ReportEvidence save(ReportEvidence evidence);
}
