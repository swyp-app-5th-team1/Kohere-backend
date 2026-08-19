package com.kohere.chat.presentation;

import com.kohere.chat.application.ChatService;
import com.kohere.chat.application.dto.InquiryResponse;
import com.kohere.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 매물 문의 REST 컨트롤러. 입력 검증·DTO 변환만 담당하고 비즈니스 로직은 응용 계층에 위임한다 (docs/convention/code-style.md §3-3).
 * 응답은 공통 래퍼로 감싼다.
 *
 * <p>문의는 매물에 종속되는 액션이므로 {@code /listings/{listingId}} 하위에 둔다. 스펙:
 * docs/api/specs/04-booking-inquiry-chat.md §2.
 *
 * <p>응용 계층 결과의 {@code created}가 true면 201, false면 200을 반환한다. 실제 방 조회·생성은 후속 구현 단계에서 완성한다.
 */
@RestController
@RequestMapping("/api/v1/listings/{listingId}")
@RequiredArgsConstructor
public class InquiryController {

  private final ChatService chatService;

  @PostMapping("/inquiries")
  public ResponseEntity<ApiResponse<InquiryResponse>> createInquiry(
      @PathVariable String listingId) {
    InquiryResponse response = chatService.createInquiry(listingId);
    HttpStatus status = response.created() ? HttpStatus.CREATED : HttpStatus.OK;
    return ResponseEntity.status(status).body(ApiResponse.success(response));
  }
}
