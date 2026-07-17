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
   * 임대인 온보딩 완료 — TERMS_AGREED→ACTIVE 전이 + 임대인 프로필 확정(name·연락처·생년월일) + userType=LANDLORD 확정 + 닉네임 자동
   * 배정. 연락처 SMS 인증 완료 확인은 호출자(auth)가 선행한다. 사업자등록번호는 온보딩에서 수집하지 않는다 — 온보딩 후 별도 검증 API로
   * 검증한다(ADR-0033·0034).
   *
   * @throws com.kohere.user.domain.TermsAgreementRequiredException 약관 미동의(PENDING)인 경우(422)
   * @throws com.kohere.user.domain.OnboardingAlreadyCompletedException 이미 ACTIVE인 경우(409)
   */
  UserProfileView completeLandlordOnboarding(long userId, LandlordOnboardingProfile profile);

  /**
   * 계정 식별·상태 조회(소셜 로그인 분기 판정용).
   *
   * @throws com.kohere.user.domain.UserNotFoundException 없거나 탈퇴한 경우
   */
  UserAccountView getAccount(long userId);

  /**
   * 회원 역할(userType — 예 {@code TENANT}·{@code LANDLORD}) 조회. auth가 임대인 전용 자원(사업자번호 검증) 접근 판정에 동기
   * 호출한다.
   *
   * @throws com.kohere.user.domain.UserNotFoundException 없거나 탈퇴한 경우
   */
  String getUserType(long userId);

  /**
   * 표시 언어(ISO 639-1) 조회. diagnosis 등 다국어 표시 모듈이 사용자 언어를 결정하기 위해 동기 호출한다(ADR-0002 Decision 5 — 즉시
   * 결과가 필요한 조회, 토큰 클레임 미사용·ADR-0029). 사용자가 고른 표시 언어({@code users.lang})를 반환하며, 미설정이면(온보딩 전·미선택)
   * 영어({@code en})로 폴백한다(에러 아님, #141 — 국가→언어 도출 폐기).
   *
   * @throws com.kohere.user.domain.UserNotFoundException 없거나 탈퇴한 경우
   */
  String getLanguage(long userId);

  /**
   * 회원 표시 이름 조회. booking 등 다른 모듈이 예약자 성명 표시를 위해 동기 호출한다(ADR-0002 공개 API 협력). 세입자는 {@code firstName
   * + " " + lastName}, 임대인은 {@code firstName}(전체 이름)이며, 온보딩 전/탈퇴 등으로 이름이 없으면 빈 문자열을 반환한다.
   *
   * @throws com.kohere.user.domain.UserNotFoundException 없거나 탈퇴한 경우
   */
  String getUserName(long userId);

  /**
   * 신청자(세입자) 프로필 조회. booking의 임대인 받은 신청 상세 조회(US-4-6, userType 분기)가 신청자 정보를 표시하기 위해 동기
   * 호출한다(ADR-0002 공개 API 협력). 성명(firstName + lastName)·성별·국적(ISO 코드·표시명)·이메일을 담으며, 임대인에게 마스킹 없이 평문
   * 노출한다(제품 결정). 온보딩 전/탈퇴 등으로 값이 없으면 {@code name}은 빈 문자열, 나머지는 {@code null}이다.
   *
   * @throws com.kohere.user.domain.UserNotFoundException 없거나 탈퇴한 경우
   */
  ApplicantProfileView getApplicantProfile(long userId);
}
