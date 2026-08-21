package com.kohere.report.application;

import com.kohere.report.domain.Report;

/** 신고 접수 결과와 이번 요청에서 실제 새 행을 만들었는지를 함께 전달한다. */
public record ReportCreationResult(Report report, boolean created) {}
