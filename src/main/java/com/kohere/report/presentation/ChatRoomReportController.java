package com.kohere.report.presentation;

import com.kohere.common.response.ApiResponse;
import com.kohere.common.security.AuthPrincipal;
import com.kohere.report.application.ReportCreationResult;
import com.kohere.report.application.ReportService;
import com.kohere.report.application.dto.ChatRoomReportResponse;
import com.kohere.report.presentation.dto.CreateChatRoomReportRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 사용자가 현재 1:1 채팅방의 상대방을 고정 사유 하나로 신고하는 REST 진입점이다. */
@RestController
@RequestMapping("/api/v1/chat-rooms/{roomId}/reports")
@RequiredArgsConstructor
public class ChatRoomReportController {

  private final ReportService reportService;

  /**
   * 신고자·상대방·증거를 클라이언트 값에 맡기지 않고 서버 정본에서 결정해 신고를 접수한다.
   *
   * <p>신규 행이면 201, 같은 사용자·같은 채팅방 요청을 다시 보냈다면 기존 접수 결과와 200을 반환한다. 응답 본문 구조는 두 경우에 같다.
   *
   * @param principal 검증된 access token에서 만든 신고자 정보
   * @param roomId 현재 채팅방 목록·상세에서 받은 서버 채팅방 ID
   * @param request 프런트가 선택한 언어 무관 신고 사유 코드 하나
   * @return DB에 커밋된 신고 접수 결과
   */
  @PostMapping
  public ResponseEntity<ApiResponse<ChatRoomReportResponse>> createReport(
      @AuthenticationPrincipal AuthPrincipal principal,
      @PathVariable Long roomId,
      @Valid @RequestBody CreateChatRoomReportRequest request) {
    // userId나 신고 대상자를 body로 받지 않아 다른 사람 명의나 임의 사용자를 신고하는 요청을 만들 수 없게 한다.
    ReportCreationResult result =
        reportService.createChatRoomReport(principal.userId(), roomId, request.reason());

    HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
    return ResponseEntity.status(status)
        .body(ApiResponse.success(ChatRoomReportResponse.from(result.report())));
  }
}
