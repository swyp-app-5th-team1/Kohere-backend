package com.kohere.notification.presentation;

import com.kohere.common.security.AuthPrincipal;
import com.kohere.notification.application.NotificationPreferenceService;
import com.kohere.notification.presentation.dto.NotificationPreferenceResponse;
import com.kohere.notification.presentation.dto.NotificationPreferenceUpdateRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 로그인 사용자의 계정 단위 채팅 푸시 수신 설정을 조회하고 변경하는 REST 컨트롤러다. */
@RestController
@RequestMapping("/api/v1/users/me/notification-preferences")
@RequiredArgsConstructor
public class NotificationPreferenceController {

  private final NotificationPreferenceService preferenceService;

  /**
   * 현재 사용자의 채팅 푸시 설정을 조회한다.
   *
   * @param principal JWT에서 검증한 현재 사용자
   * @return 설정 행이 없으면 기본값 {@code true}, 있으면 저장된 값
   */
  @GetMapping
  public NotificationPreferenceResponse get(@AuthenticationPrincipal AuthPrincipal principal) {
    return new NotificationPreferenceResponse(
        preferenceService.isChatPushEnabled(principal.userId()));
  }

  /**
   * 현재 사용자의 모든 채팅 푸시 수신 여부를 명시적인 값으로 저장한다.
   *
   * @param principal JWT에서 검증한 현재 사용자
   * @param request null이 아닌 새 설정값
   * @return DB에 저장된 최종 설정
   */
  @PatchMapping
  public NotificationPreferenceResponse update(
      @AuthenticationPrincipal AuthPrincipal principal,
      @Valid @RequestBody NotificationPreferenceUpdateRequest request) {
    boolean updated =
        preferenceService.updateChatPushEnabled(principal.userId(), request.chatPushEnabled());
    return new NotificationPreferenceResponse(updated);
  }
}
