package com.kohere.user.api;

/**
 * 회원 계정 공개 명령·쿼리. auth가 소셜 로그인/약관 동의/온보딩 흐름에서 동기로 호출한다(공개 API 협력, ADR-0002). user가 User 애그리거트·상태를
 * 소유하고 auth는 식별자(userId)만 참조한다.
 */
public interface UserAccountService {

  /** 신규 회원을 PENDING으로 생성하고 식별자를 반환한다(소셜 로그인 신규 분기). */
  long createPendingUser();

  /**
   * 약관 동의 — PENDING→TERMS_AGREED 전이 + 동의 3종·약관 버전·동의 시각 확정. 필수 약관 동의 검증은 호출자(auth)가 선행한다. 이미
   * TERMS_AGREED면 멱등 처리한다.
   *
   * @throws com.kohere.user.domain.OnboardingAlreadyCompletedException 이미 ACTIVE인 경우(409)
   * @throws com.kohere.user.domain.UserNotFoundException 없거나 탈퇴한 경우
   */
  TermsAgreementView agreeToTerms(long userId, boolean marketingAgreed);

  /**
   * 온보딩 완료 — TERMS_AGREED→ACTIVE 전이 + 프로필 확정 + 닉네임 자동 배정. email 인증 완료 확인은 호출자(auth)가 선행한다.
   *
   * @throws com.kohere.user.domain.TermsAgreementRequiredException 약관 미동의(PENDING)인 경우(422)
   * @throws com.kohere.user.domain.OnboardingAlreadyCompletedException 이미 ACTIVE인 경우(409)
   * @throws com.kohere.common.exception.InvalidInputException gender·occupation·visaType·country 값이
   *     유효하지 않은 경우(400)
   */
  UserProfileView completeOnboarding(long userId, OnboardingProfile profile);

  /**
   * 계정 식별·상태 조회(소셜 로그인 분기 판정용).
   *
   * @throws com.kohere.user.domain.UserNotFoundException 없거나 탈퇴한 경우
   */
  UserAccountView getAccount(long userId);

  /**
   * 표시 언어(ISO 639-1) 조회. diagnosis 등 다국어 표시 모듈이 사용자 언어를 결정하기 위해 동기 호출한다(ADR-0002 Decision 5 — 즉시
   * 결과가 필요한 조회, 토큰 클레임 미사용·ADR-0029). 등록 국가({@code countries.lang})로 도출하며, 온보딩 전이거나 국가→언어 미매핑이면
   * 영어({@code en})로 폴백한다(에러 아님).
   *
   * @throws com.kohere.user.domain.UserNotFoundException 없거나 탈퇴한 경우
   */
  String getLanguage(long userId);
}
