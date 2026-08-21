package com.kohere.report.domain;

/** 신고 처리 상태. 현재 사용자 접수 단계에서는 항상 {@link #RECEIVED}이며 관리자 상태 전이는 후속 범위다. */
public enum ReportStatus {
  RECEIVED
}
