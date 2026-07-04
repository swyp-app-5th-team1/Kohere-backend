package com.kohere.user.domain;

import java.time.Instant;
import java.time.LocalDate;
import lombok.Builder;
import lombok.Getter;

/**
 * 회원 애그리거트 루트. 영속 기술(JPA)에 의존하지 않는 순수 도메인 모델이며, 상태 전이·프로필 변경은 불변식을 강제하는 도메인 메서드로만 수행한다(불변 객체 — 새
 * 인스턴스 반환). 영속 매핑은 infrastructure 어댑터가 처리한다(docs/convention/code-style.md §3-3).
 *
 * <p>상태 전이는 단방향 PENDING→TERMS_AGREED→ACTIVE→WITHDRAWN만 허용한다(domain-model §2, ADR-0014). 약관 동의와 온보딩은
 * 분리된 단계다. 탈퇴 시 식별 PII를 즉시 익명화한다.
 */
@Getter
@Builder(toBuilder = true)
public class User {

  private final Long id;
  private final String firstName;
  private final String lastName;
  private final String nickname;
  private final Gender gender;
  private final LocalDate birthDate;
  private final String country;
  private final Occupation occupation;
  private final String email;
  private final VisaType visaType;
  private final UserType userType;
  private final String phoneNumber;
  private final String businessRegistrationNumberHash;
  private final UserStatus status;
  private final boolean termsOfServiceAgreed;
  private final boolean privacyPolicyAgreed;
  private final boolean marketingAgreed;
  private final String termsVersion;
  private final Instant agreedAt;
  private final Instant createdAt;
  private final Instant updatedAt;
  private final Instant withdrawnAt;

  /**
   * 소셜 검증만 완료한 신규 회원(PENDING). 약관 동의·프로필은 이후 단계에서 채운다. 역할은 기본 {@code TENANT}이며 임대인 온보딩 시 {@code
   * LANDLORD}로 확정한다(V8 DEFAULT 'TENANT'와 정합, 온보딩 전에는 미확정 의미).
   */
  public static User createPending(Instant now) {
    return User.builder()
        .userType(UserType.TENANT)
        .status(UserStatus.PENDING)
        .createdAt(now)
        .updatedAt(now)
        .build();
  }

  /**
   * 약관 동의(PENDING→TERMS_AGREED). 동의 3종·약관 버전·동의 시각(agreedAt)을 확정한다. 필수 약관(이용약관·개인정보)은 항상 동의
   * 처리된다(미동의는 auth가 호출 전 차단). 이미 TERMS_AGREED면 멱등 무변경, ACTIVE/WITHDRAWN면 예외.
   *
   * @throws OnboardingAlreadyCompletedException 이미 ACTIVE(또는 WITHDRAWN)인 경우
   */
  public User agreeToTerms(boolean marketingAgreed, String termsVersion, Instant now) {
    if (status == UserStatus.TERMS_AGREED) {
      return this; // 중복 호출 멱등 — 상태·동의 불변
    }
    if (status != UserStatus.PENDING) {
      throw new OnboardingAlreadyCompletedException();
    }
    return toBuilder()
        .termsOfServiceAgreed(true)
        .privacyPolicyAgreed(true)
        .marketingAgreed(marketingAgreed)
        .termsVersion(termsVersion)
        .agreedAt(now)
        .status(UserStatus.TERMS_AGREED)
        .updatedAt(now)
        .build();
  }

  /**
   * 온보딩 완료(TERMS_AGREED→ACTIVE). 프로필(이름·성별·생년월일·국적·직업·이메일·비자)과 시스템 배정 닉네임을 확정한다. 약관 동의는 약관 동의 단계에서
   * 이미 기록됐다. 이메일은 auth가 인증 완료를 선행 확인한다.
   *
   * @throws TermsAgreementRequiredException 약관 미동의(PENDING)인 경우(약관 동의 선행 필요, 422)
   * @throws OnboardingAlreadyCompletedException 이미 ACTIVE(또는 WITHDRAWN)인 경우(409)
   */
  public User completeOnboarding(
      String firstName,
      String lastName,
      String nickname,
      Gender gender,
      LocalDate birthDate,
      String country,
      Occupation occupation,
      String email,
      VisaType visaType,
      Instant now) {
    if (status == UserStatus.PENDING) {
      throw new TermsAgreementRequiredException();
    }
    if (status != UserStatus.TERMS_AGREED) {
      throw new OnboardingAlreadyCompletedException();
    }
    return toBuilder()
        .firstName(firstName)
        .lastName(lastName)
        .nickname(nickname)
        .gender(gender)
        .birthDate(birthDate)
        .country(country)
        .occupation(occupation)
        .email(email)
        .visaType(visaType)
        .userType(UserType.TENANT)
        .status(UserStatus.ACTIVE)
        .updatedAt(now)
        .build();
  }

  /**
   * 임대인 온보딩 완료(TERMS_AGREED→ACTIVE). 단일 {@code name}(성·이름 합친 전체 이름 — {@code firstName}에 보관, {@code
   * lastName} 미사용)·연락처와 시스템 배정 닉네임을 확정하고 {@code userType}을 LANDLORD로 확정한다. 연락처는 auth가 SMS 인증을 선행
   * 확인한다. 사업자등록번호 해시는 온보딩에서 확정하지 않는다({@code null} 유지) — 온보딩 후 별도 검증 흐름(매물 등록 시점)에서 채운다(ADR-0033).
   * 임대인은 성별·국적·직업·비자정보·생년월일·이메일을 수집하지 않는다(ADR-0034).
   *
   * @throws TermsAgreementRequiredException 약관 미동의(PENDING)인 경우(422)
   * @throws OnboardingAlreadyCompletedException 이미 ACTIVE(또는 WITHDRAWN)인 경우(409)
   */
  public User completeLandlordOnboarding(
      String name, String phoneNumber, String nickname, Instant now) {
    if (status == UserStatus.PENDING) {
      throw new TermsAgreementRequiredException();
    }
    if (status != UserStatus.TERMS_AGREED) {
      throw new OnboardingAlreadyCompletedException();
    }
    return toBuilder()
        .firstName(name)
        .lastName(null)
        .nickname(nickname)
        .phoneNumber(phoneNumber)
        .businessRegistrationNumberHash(null)
        .userType(UserType.LANDLORD)
        .status(UserStatus.ACTIVE)
        .updatedAt(now)
        .build();
  }

  /**
   * 프로필 부분 수정. 전송한(=null 아님) 필드만 변경하고 미전송 필드는 유지한다. {@code email}·{@code nickname}은 이 경로로 수정하지
   * 않는다(이메일은 재인증, 닉네임은 시스템 배정).
   */
  public User updateProfile(
      String firstName,
      String lastName,
      Gender gender,
      LocalDate birthDate,
      String country,
      Occupation occupation,
      VisaType visaType,
      Boolean marketingAgreed,
      Instant now) {
    var builder = toBuilder();
    if (firstName != null) {
      builder.firstName(firstName);
    }
    if (lastName != null) {
      builder.lastName(lastName);
    }
    if (gender != null) {
      builder.gender(gender);
    }
    if (birthDate != null) {
      builder.birthDate(birthDate);
    }
    if (country != null) {
      builder.country(country);
    }
    if (occupation != null) {
      builder.occupation(occupation);
    }
    if (visaType != null) {
      builder.visaType(visaType);
    }
    if (marketingAgreed != null) {
      builder.marketingAgreed(marketingAgreed);
    }
    return builder.updatedAt(now).build();
  }

  /**
   * 임대인 프로필 부분 수정. 전송한(=null 아님) 필드만 변경한다 — {@code name}(전체 이름)은 {@code firstName}에 보관하고({@code
   * lastName} 미사용), {@code phoneNumber}·{@code marketingAgreed}를 갱신한다. 연락처 변경 시 SMS 재인증 완료 확인은
   * 호출자(user 응용)가 선행한다(ADR-0034). 세입자 전용 필드(성별·국적·직업·비자정보)는 임대인 경로에서 다루지 않는다.
   */
  public User updateLandlordProfile(
      String name, String phoneNumber, Boolean marketingAgreed, Instant now) {
    var builder = toBuilder();
    if (name != null) {
      builder.firstName(name);
    }
    if (phoneNumber != null) {
      builder.phoneNumber(phoneNumber);
    }
    if (marketingAgreed != null) {
      builder.marketingAgreed(marketingAgreed);
    }
    return builder.updatedAt(now).build();
  }

  /**
   * 회원 탈퇴(→WITHDRAWN). 식별 PII(이름·닉네임·생년월일·국적·직업·이메일·비자)를 즉시 익명화(제거)하고 탈퇴 시각을 기록한다(ADR-0014). 행 자체는
   * 보존한다. 닉네임도 NULL로 비워 유니크 슬롯을 회수한다. 동의 메타데이터(약관 동의 여부·termsVersion·agreedAt)는 식별 PII가 아니므로 동의 증빙을
   * 위해 보존한다.
   *
   * @throws UserAlreadyWithdrawnException 이미 WITHDRAWN인 경우
   */
  public User withdraw(Instant now) {
    if (status == UserStatus.WITHDRAWN) {
      throw new UserAlreadyWithdrawnException();
    }
    return toBuilder()
        .firstName(null)
        .lastName(null)
        .nickname(null)
        .gender(null)
        .birthDate(null)
        .country(null)
        .occupation(null)
        .email(null)
        .visaType(null)
        .phoneNumber(null)
        .businessRegistrationNumberHash(null)
        .status(UserStatus.WITHDRAWN)
        .withdrawnAt(now)
        .updatedAt(now)
        .build();
  }
}
