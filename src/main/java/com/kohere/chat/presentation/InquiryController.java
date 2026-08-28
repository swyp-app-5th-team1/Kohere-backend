package com.kohere.chat.presentation;

import com.kohere.chat.application.ChatService;
import com.kohere.chat.application.dto.InquiryResponse;
import com.kohere.common.response.ApiResponse;
import com.kohere.common.security.AuthPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 매물 문의를 시작할 때 임대인과의 1:1 채팅방을 보장하는 REST 진입점이다.
 *
 * <p>문의는 매물에 종속된 동작이므로 {@code /listings/{listingId}} 아래에 둔다. 새 방이면 매물 요약 INQUIRY_CARD도 첫 메시지로 함께
 * 저장한다. 기존 방이면 현재 이력에서 보이는 문의서가 마지막 메시지일 때만 연속 중복을 생략하고, 그 뒤에 대화나 신청서가 있으면 새 문의서를 저장한다. 이 API가
 * roomId를 반환한 뒤 실제 텍스트 전송은 STOMP가 담당한다.
 *
 * <p>계약: docs/architecture/chat/02-api-contracts.md §5.1.
 */
@RestController
@RequestMapping("/api/v1/listings/{listingId}")
@RequiredArgsConstructor
public class InquiryController {

  private final ChatService chatService;

  /**
   * 해당 매물의 기존 채팅방을 조회하거나 없으면 문의서와 함께 새로 만든다.
   *
   * <p>신규 생성은 {@code 201 Created}, 기존 방을 반환하면 {@code 200 OK}다. 두 경우 모두 앱은 반환된 동일한 {@code
   * chatRoomId}로 화면을 열고 메시지 이력을 조회한다. {@code created}는 방 생성 여부이며 문의서 저장 여부가 아니다. 문의서는 응답 본문이 아니라
   * 메시지 이력의 {@code INQUIRY_CARD}로 받는다.
   *
   * @param principal JWT 검증을 마친 로그인 사용자. 이 ID를 임차인으로 사용한다.
   * @param listingId 문의할 매물 식별자
   * @return 보장된 채팅방 ID와 이번 요청의 생성 여부
   */
  @PostMapping("/inquiries")
  public ResponseEntity<ApiResponse<InquiryResponse>> createInquiry(
      @AuthenticationPrincipal AuthPrincipal principal, @PathVariable String listingId) {
    // tenantId를 body나 path에서 받지 않아 다른 사용자 이름으로 채팅방을 만드는 요청 변조를 막는다.
    InquiryResponse response = chatService.createInquiry(principal.userId(), listingId);
    HttpStatus status = response.created() ? HttpStatus.CREATED : HttpStatus.OK;
    return ResponseEntity.status(status).body(ApiResponse.success(response));
  }
}
