package com.kohere.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kohere.auth.domain.PhoneNotVerifiedException;
import com.kohere.common.exception.InvalidInputException;
import com.kohere.user.api.PhoneVerificationChecker;
import com.kohere.user.application.dto.UserProfileResponse;
import com.kohere.user.domain.CountryRepository;
import com.kohere.user.domain.Gender;
import com.kohere.user.domain.Occupation;
import com.kohere.user.domain.User;
import com.kohere.user.domain.UserRepository;
import com.kohere.user.domain.UserType;
import com.kohere.user.domain.VisaType;
import com.kohere.user.presentation.dto.UpdateProfileRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * {@link UserService} 단위 테스트(Mockito) — {@code GET/PATCH /users/me}의 userType 분기(세입자/임대인 응답 shape),
 * 임대인 연락처 변경 시 SMS 재인증 게이트(US-1-5, ADR-0034), 세입자 country 검증. 도메인 리포지토리·연락처 인증 확인 SPI({@link
 * PhoneVerificationChecker})를 모킹한다.
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

  private static final Instant NOW = Instant.parse("2026-06-17T00:00:00Z");

  @Mock private UserRepository userRepository;
  @Mock private CountryRepository countryRepository;
  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private PhoneVerificationChecker phoneVerificationChecker;

  private UserService userService;

  @BeforeEach
  void setUp() {
    userService =
        new UserService(
            userRepository, countryRepository, eventPublisher, phoneVerificationChecker);
  }

  @Test
  void getMyProfile_tenant_returnsTenantShapeWithUserType() {
    when(userRepository.findById(1L)).thenReturn(Optional.of(tenant(1L)));
    when(countryRepository.findByCode("KR")).thenReturn(Optional.empty());

    UserProfileResponse res = userService.getMyProfile(1L);

    assertThat(res.userType()).isEqualTo(UserType.TENANT);
    assertThat(res.firstName()).isEqualTo("Gil");
    assertThat(res.name()).isNull(); // 세입자는 name 없음 → 응답에서 생략
    assertThat(res.phoneNumber()).isNull(); // 세입자는 연락처 없음 → 생략
    assertThat(res.occupation()).isEqualTo(Occupation.UNDERGRADUATE_STUDENT);
  }

  @Test
  void getMyProfile_landlord_returnsNameAndPlaintextPhone() {
    when(userRepository.findById(1L)).thenReturn(Optional.of(landlord(1L)));

    UserProfileResponse res = userService.getMyProfile(1L);

    assertThat(res.userType()).isEqualTo(UserType.LANDLORD);
    assertThat(res.name()).isEqualTo("Kim Imdae");
    assertThat(res.firstName()).isNull(); // 임대인은 firstName 노출 안 함(name으로)
    assertThat(res.phoneNumber()).isEqualTo("01012345678"); // 본인 조회 — 평문
    assertThat(res.occupation()).isNull(); // 세입자 전용 필드 생략
  }

  @Test
  void updateMyProfile_landlord_changingPhone_verified_updatesPhone() {
    when(userRepository.findById(1L)).thenReturn(Optional.of(landlord(1L)));
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

    UserProfileResponse res = userService.updateMyProfile(1L, landlordPatch("01099998888"));

    verify(phoneVerificationChecker).assertPhoneVerified(1L, "01099998888");
    assertThat(res.phoneNumber()).isEqualTo("01099998888");
  }

  @Test
  void updateMyProfile_landlord_changingPhone_notVerified_throwsAndDoesNotSave() {
    when(userRepository.findById(1L)).thenReturn(Optional.of(landlord(1L)));
    doThrow(new PhoneNotVerifiedException())
        .when(phoneVerificationChecker)
        .assertPhoneVerified(1L, "01099998888");

    assertThatThrownBy(() -> userService.updateMyProfile(1L, landlordPatch("01099998888")))
        .isInstanceOf(PhoneNotVerifiedException.class);

    verify(userRepository, never()).save(any());
  }

  @Test
  void updateMyProfile_landlord_samePhone_skipsVerification() {
    when(userRepository.findById(1L)).thenReturn(Optional.of(landlord(1L)));
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

    userService.updateMyProfile(1L, landlordPatch("01012345678")); // 현재와 동일 번호

    verify(phoneVerificationChecker, never()).assertPhoneVerified(anyLong(), anyString());
  }

  @Test
  void updateMyProfile_landlord_nameAndMarketingOnly_skipsPhoneVerification() {
    when(userRepository.findById(1L)).thenReturn(Optional.of(landlord(1L)));
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    UpdateProfileRequest req =
        new UpdateProfileRequest(null, null, null, null, null, null, null, "New Name", null, true);

    UserProfileResponse res = userService.updateMyProfile(1L, req);

    verify(phoneVerificationChecker, never()).assertPhoneVerified(anyLong(), anyString());
    assertThat(res.name()).isEqualTo("New Name");
    assertThat(res.marketingAgreed()).isTrue();
  }

  @Test
  void updateMyProfile_tenant_invalidCountry_throwsAndDoesNotSave() {
    when(userRepository.findById(1L)).thenReturn(Optional.of(tenant(1L)));
    when(countryRepository.existsByCode("XX")).thenReturn(false);
    UpdateProfileRequest req =
        new UpdateProfileRequest(null, null, null, null, "XX", null, null, null, null, null);

    assertThatThrownBy(() -> userService.updateMyProfile(1L, req))
        .isInstanceOf(InvalidInputException.class);

    verify(userRepository, never()).save(any());
  }

  private static UpdateProfileRequest landlordPatch(String phoneNumber) {
    return new UpdateProfileRequest(
        null, null, null, null, null, null, null, null, phoneNumber, null);
  }

  private static User tenant(long id) {
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
            NOW)
        .toBuilder()
        .id(id)
        .build();
  }

  private static User landlord(long id) {
    return User.createPending(NOW)
        .agreeToTerms(true, "v1.0", NOW)
        .completeLandlordOnboarding("Kim Imdae", "01012345678", "CalmFox", NOW)
        .toBuilder()
        .id(id)
        .build();
  }
}
