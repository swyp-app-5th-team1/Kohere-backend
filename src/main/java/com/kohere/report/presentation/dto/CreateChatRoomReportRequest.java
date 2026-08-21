package com.kohere.report.presentation.dto;

import com.kohere.report.domain.ReportReason;
import jakarta.validation.constraints.NotNull;

/**
 * 채팅방 신고 요청 바디다.
 *
 * <p>프런트는 화면 언어에 맞는 문구를 보여 주고 선택된 고정 코드만 보낸다. 자유 입력 상세 사유, 신고자, 신고 대상자, 증거 메시지는 서버가 받지 않는다.
 */
public record CreateChatRoomReportRequest(@NotNull ReportReason reason) {}
