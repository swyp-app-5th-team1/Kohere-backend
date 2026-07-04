package com.kohere.user.api;

/**
 * 연락처(SMS) 인증 완료 확인 SPI. 연락처 인증 상태는 auth 모듈이 소유하므로(US-1-10 온보딩·US-1-5 프로필 변경 공용 {@code
 * /auth/phone/**}), user가 인터페이스를 정의하고 auth가 구현한다(의존 역전 — user는 auth를 참조하지 않는다, ADR-0002).
 *
 * <p>임대인이 {@code PATCH /users/me}로 연락처를 변경할 때, 새 번호가 사전 SMS 재인증(§4-1·§4-2)으로 검증 완료됐는지 확인하는 데 쓴다.
 * 미검증·불일치면 구현이 422 {@code AUTH_PHONE_NOT_VERIFIED}를 던진다(ADR-0034).
 */
public interface PhoneVerificationChecker {

  /**
   * 제출 {@code phoneNumber}가 해당 사용자의 검증 완료 마커와 일치하는지 확인한다.
   *
   * @throws com.kohere.auth.domain.PhoneNotVerifiedException 미검증이거나 검증한 번호와 불일치(422)
   */
  void assertPhoneVerified(long userId, String phoneNumber);
}
