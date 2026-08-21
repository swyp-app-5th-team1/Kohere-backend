package com.kohere.chat.api;

import java.time.Instant;

/** report 모듈에 전달할 TEXT 원문 한 건의 공개 불변 값이다. */
public record ChatReportMessageSnapshot(
    Long messageId, Long senderId, String originalContent, Instant sentAt) {}
