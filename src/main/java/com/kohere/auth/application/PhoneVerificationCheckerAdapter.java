package com.kohere.auth.application;

import com.kohere.user.api.PhoneVerificationChecker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@link PhoneVerificationChecker}(user가 정의한 SPI)의 auth 구현. 프로필 연락처 변경(US-1-5) 시 user가 동기 호출해 새 번호의
 * SMS 인증 완료를 확인하도록, auth의 {@link PhoneVerificationService#assertVerified}에 위임한다(의존 역전 — user→auth
 * 컴파일 의존 없음, ADR-0002·0034).
 */
@Component
@RequiredArgsConstructor
class PhoneVerificationCheckerAdapter implements PhoneVerificationChecker {

  private final PhoneVerificationService phoneVerificationService;

  @Override
  public void assertPhoneVerified(long userId, String phoneNumber) {
    phoneVerificationService.assertVerified(userId, phoneNumber);
  }
}
