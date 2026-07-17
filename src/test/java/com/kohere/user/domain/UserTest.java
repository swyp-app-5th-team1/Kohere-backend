package com.kohere.user.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * {@link User} 도메인 불변식 — 상태 전이(PENDING→TERMS_AGREED→ACTIVE→WITHDRAWN)·약관 동의·온보딩 선행조건·부분 수정·탈퇴 PII
 * 익명화(domain-model §2, ADR-0014).
 */
class UserTest {

  private static final Instant NOW = Instant.parse("2026-06-17T00:00:00Z");

  @Test
  void createPending_startsInPending() {
    User user = User.createPending(NOW);

    assertThat(user.getStatus()).isEqualTo(UserStatus.PENDING);
    assertThat(user.getCreatedAt()).isEqualTo(NOW);
  }

  @Test
  void agreeToTerms_transitionsPendingToTermsAgreed() {
    User agreed = User.createPending(NOW).agreeToTerms(true, "v1.0", NOW);

    assertThat(agreed.getStatus()).isEqualTo(UserStatus.TERMS_AGREED);
    assertThat(agreed.isTermsOfServiceAgreed()).isTrue();
    assertThat(agreed.isPrivacyPolicyAgreed()).isTrue();
    assertThat(agreed.isMarketingAgreed()).isTrue();
    assertThat(agreed.getTermsVersion()).isEqualTo("v1.0");
    assertThat(agreed.getAgreedAt()).isEqualTo(NOW);
  }

  @Test
  void agreeToTerms_whenAlreadyTermsAgreed_isIdempotent() {
    User agreed = User.createPending(NOW).agreeToTerms(true, "v1.0", NOW);

    User again = agreed.agreeToTerms(false, "v1.0", NOW);

    assertThat(again.getStatus()).isEqualTo(UserStatus.TERMS_AGREED);
    assertThat(again).isSameAs(agreed);
  }

  @Test
  void completeOnboarding_transitionsTermsAgreedToActive() {
    User termsAgreed = User.createPending(NOW).agreeToTerms(true, "v1.0", NOW);

    User active =
        termsAgreed.completeOnboarding(
            "Gil",
            "Hong",
            "BraveOtter",
            Gender.MALE,
            LocalDate.of(1990, 1, 1),
            "KR",
            Occupation.UNDERGRADUATE_STUDENT,
            "gil@example.com",
            VisaType.SHORT_TERM_VISIT,
            Language.EN,
            NOW);

    assertThat(active.getStatus()).isEqualTo(UserStatus.ACTIVE);
    assertThat(active.getFirstName()).isEqualTo("Gil");
    assertThat(active.getNickname()).isEqualTo("BraveOtter");
    assertThat(active.getCountry()).isEqualTo("KR");
    assertThat(active.getOccupation()).isEqualTo(Occupation.UNDERGRADUATE_STUDENT);
    assertThat(active.getEmail()).isEqualTo("gil@example.com");
    // 동의는 약관 동의 단계에서 이미 확정됨
    assertThat(active.isTermsOfServiceAgreed()).isTrue();
  }

  @Test
  void completeOnboarding_whenPending_throwsTermsAgreementRequired() {
    User pending = User.createPending(NOW);

    assertThatThrownBy(
            () ->
                pending.completeOnboarding(
                    "Gil",
                    "Hong",
                    "BraveOtter",
                    Gender.MALE,
                    LocalDate.of(1990, 1, 1),
                    "KR",
                    Occupation.UNDERGRADUATE_STUDENT,
                    "gil@example.com",
                    VisaType.SHORT_TERM_VISIT,
                    Language.EN,
                    NOW))
        .isInstanceOf(TermsAgreementRequiredException.class);
  }

  @Test
  void completeOnboarding_whenAlreadyActive_throws() {
    User active = activeUser();

    assertThatThrownBy(
            () ->
                active.completeOnboarding(
                    "A",
                    "B",
                    "CalmFox",
                    Gender.FEMALE,
                    LocalDate.of(1995, 5, 5),
                    "VN",
                    Occupation.ETC,
                    "a@example.com",
                    VisaType.STUDENTS_TRAINEES,
                    Language.EN,
                    NOW))
        .isInstanceOf(OnboardingAlreadyCompletedException.class);
  }

  @Test
  void updateProfile_changesOnlyProvidedFields() {
    User active = activeUser();

    User updated =
        active.updateProfile("Updated", null, null, null, null, null, null, null, null, NOW);

    assertThat(updated.getFirstName()).isEqualTo("Updated");
    assertThat(updated.getLastName()).isEqualTo(active.getLastName());
    assertThat(updated.getCountry()).isEqualTo(active.getCountry());
    assertThat(updated.getVisaType()).isEqualTo(active.getVisaType());
  }

  @Test
  void withdraw_anonymizesPiiAndRecordsTimestamp() {
    User active = activeUser();

    User withdrawn = active.withdraw(NOW);

    assertThat(withdrawn.getStatus()).isEqualTo(UserStatus.WITHDRAWN);
    assertThat(withdrawn.getWithdrawnAt()).isEqualTo(NOW);
    assertThat(withdrawn.getFirstName()).isNull();
    assertThat(withdrawn.getLastName()).isNull();
    assertThat(withdrawn.getNickname()).isNull();
    assertThat(withdrawn.getCountry()).isNull();
    assertThat(withdrawn.getOccupation()).isNull();
    assertThat(withdrawn.getEmail()).isNull();
    assertThat(withdrawn.getVisaType()).isNull();
    assertThat(withdrawn.getBirthDate()).isNull();
  }

  @Test
  void withdraw_whenAlreadyWithdrawn_throws() {
    User withdrawn = activeUser().withdraw(NOW);

    assertThatThrownBy(() -> withdrawn.withdraw(NOW))
        .isInstanceOf(UserAlreadyWithdrawnException.class);
  }

  @Test
  void completeLandlordOnboarding_transitionsTermsAgreedToActiveAsLandlord() {
    User termsAgreed = User.createPending(NOW).agreeToTerms(true, "v1.0", NOW);

    User active =
        termsAgreed.completeLandlordOnboarding(
            "Kim Imdae", "01012345678", LocalDate.of(1988, 5, 20), "CalmFox", NOW);

    assertThat(active.getStatus()).isEqualTo(UserStatus.ACTIVE);
    assertThat(active.getUserType()).isEqualTo(UserType.LANDLORD);
    // 단일 name은 firstName에 보관하고 lastName은 미사용(null)
    assertThat(active.getFirstName()).isEqualTo("Kim Imdae");
    assertThat(active.getLastName()).isNull();
    assertThat(active.getPhoneNumber()).isEqualTo("01012345678");
    // 사업자번호 해시는 온보딩에서 확정하지 않는다(온보딩 후 매물 등록 시점에 채움, ADR-0033)
    assertThat(active.getBusinessRegistrationNumberHash()).isNull();
    assertThat(active.getNickname()).isEqualTo("CalmFox");
    // 임대인은 성별·직업·비자·이메일을 수집하지 않는다(생년월일은 세입자와 동일하게 수집 — #131)
    assertThat(active.getGender()).isNull();
    assertThat(active.getOccupation()).isNull();
    assertThat(active.getVisaType()).isNull();
    assertThat(active.getEmail()).isNull();
    assertThat(active.getBirthDate()).isEqualTo(LocalDate.of(1988, 5, 20));
    // 국적·표시 언어는 서버가 KR·ko로 고정 부여한다(ADR-0034 개정, #141)
    assertThat(active.getCountry()).isEqualTo("KR");
    assertThat(active.getLang()).isEqualTo(Language.KO);
  }

  @Test
  void completeLandlordOnboarding_whenPending_throwsTermsAgreementRequired() {
    User pending = User.createPending(NOW);

    assertThatThrownBy(
            () ->
                pending.completeLandlordOnboarding(
                    "Kim Imdae", "01012345678", LocalDate.of(1988, 5, 20), "CalmFox", NOW))
        .isInstanceOf(TermsAgreementRequiredException.class);
  }

  @Test
  void completeLandlordOnboarding_whenAlreadyActive_throws() {
    User active = activeUser();

    assertThatThrownBy(
            () ->
                active.completeLandlordOnboarding(
                    "Kim Imdae", "01012345678", LocalDate.of(1988, 5, 20), "CalmFox", NOW))
        .isInstanceOf(OnboardingAlreadyCompletedException.class);
  }

  @Test
  void withdraw_landlord_anonymizesPhoneAndBusinessHash() {
    User landlord =
        User.createPending(NOW)
            .agreeToTerms(true, "v1.0", NOW)
            .completeLandlordOnboarding(
                "Kim Imdae", "01012345678", LocalDate.of(1988, 5, 20), "CalmFox", NOW);

    User withdrawn = landlord.withdraw(NOW);

    assertThat(withdrawn.getStatus()).isEqualTo(UserStatus.WITHDRAWN);
    assertThat(withdrawn.getPhoneNumber()).isNull();
    assertThat(withdrawn.getBusinessRegistrationNumberHash()).isNull();
  }

  @Test
  void updateLandlordProfile_changesOnlyProvidedFields() {
    User landlord = activeLandlord();

    User updated = landlord.updateLandlordProfile("New Name", null, null, NOW);

    // 단일 name은 firstName에 보관, 미전송 연락처·마케팅은 유지
    assertThat(updated.getFirstName()).isEqualTo("New Name");
    assertThat(updated.getPhoneNumber()).isEqualTo(landlord.getPhoneNumber());
    assertThat(updated.isMarketingAgreed()).isEqualTo(landlord.isMarketingAgreed());
    assertThat(updated.getUserType()).isEqualTo(UserType.LANDLORD);
  }

  @Test
  void updateLandlordProfile_updatesPhoneAndMarketing() {
    User landlord = activeLandlord();

    User updated = landlord.updateLandlordProfile(null, "01099998888", true, NOW);

    assertThat(updated.getPhoneNumber()).isEqualTo("01099998888");
    assertThat(updated.isMarketingAgreed()).isTrue();
    // name 미전송이면 firstName(전체 이름) 유지
    assertThat(updated.getFirstName()).isEqualTo(landlord.getFirstName());
  }

  private static User activeLandlord() {
    return User.createPending(NOW)
        .agreeToTerms(true, "v1.0", NOW)
        .completeLandlordOnboarding(
            "Kim Imdae", "01012345678", LocalDate.of(1988, 5, 20), "CalmFox", NOW);
  }

  private static User activeUser() {
    return User.createPending(NOW)
        .agreeToTerms(true, "v1.0", NOW)
        .completeOnboarding(
            "Gil",
            "Hong",
            "BraveOtter",
            Gender.MALE,
            LocalDate.of(1990, 1, 1),
            "KR",
            Occupation.UNDERGRADUATE_STUDENT,
            "gil@example.com",
            VisaType.SHORT_TERM_VISIT,
            Language.EN,
            NOW);
  }
}
