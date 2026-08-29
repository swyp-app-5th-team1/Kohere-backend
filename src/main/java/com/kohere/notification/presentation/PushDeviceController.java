package com.kohere.notification.presentation;

import com.kohere.common.security.AuthPrincipal;
import com.kohere.notification.application.PushDeviceService;
import com.kohere.notification.presentation.dto.PushDeviceRegisterRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 로그인 사용자의 앱 설치본별 FCM 토큰 등록과 삭제를 제공하는 REST 컨트롤러다. */
@RestController
@RequestMapping("/api/v1/users/me/push-devices")
@RequiredArgsConstructor
public class PushDeviceController {

  private final PushDeviceService pushDeviceService;

  /**
   * 앱 실행·로그인 또는 FCM 토큰 갱신 시 현재 설치본 정보를 등록한다.
   *
   * @param principal JWT에서 검증한 현재 사용자
   * @param installationId 앱이 생성하고 보관하는 설치본 UUID
   * @param request 현재 FCM 토큰과 플랫폼
   */
  @PutMapping("/{installationId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void register(
      @AuthenticationPrincipal AuthPrincipal principal,
      @PathVariable UUID installationId,
      @Valid @RequestBody PushDeviceRegisterRequest request) {
    pushDeviceService.register(
        principal.userId(), installationId, request.fcmToken(), request.platform());
  }

  /**
   * 로그아웃 시 현재 사용자의 해당 설치본을 삭제해 이후 푸시 발송 대상에서 제외한다.
   *
   * @param principal JWT에서 검증한 현재 사용자
   * @param installationId 삭제할 앱 설치본 UUID
   */
  @DeleteMapping("/{installationId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(
      @AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID installationId) {
    pushDeviceService.delete(principal.userId(), installationId);
  }
}
