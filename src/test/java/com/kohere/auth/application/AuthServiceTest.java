package com.kohere.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kohere.auth.application.dto.BusinessVerifyResponse;
import com.kohere.auth.application.dto.EmailVerificationCodeResponse;
import com.kohere.auth.application.dto.EmailVerifyResponse;
import com.kohere.auth.application.dto.OnboardingResponse;
import com.kohere.auth.application.dto.PhoneVerificationCodeResponse;
import com.kohere.auth.application.dto.PhoneVerifyResponse;
import com.kohere.auth.application.dto.SocialLoginResponse;
import com.kohere.auth.application.dto.TermsResponse;
import com.kohere.auth.application.dto.TokenResponse;
import com.kohere.auth.domain.AppleAuthClient;
import com.kohere.auth.domain.EmailNotVerifiedException;
import com.kohere.auth.domain.InvalidRefreshTokenException;
import com.kohere.auth.domain.LandlordOnlyException;
import com.kohere.auth.domain.MissingCredentialException;
import com.kohere.auth.domain.OidcTokenVerifier;
import com.kohere.auth.domain.OidcUser;
import com.kohere.auth.domain.OnboardingAlreadyCompletedException;
import com.kohere.auth.domain.PhoneNotVerifiedException;
import com.kohere.auth.domain.Provider;
import com.kohere.auth.domain.RefreshToken;
import com.kohere.auth.domain.RefreshTokenHasher;
import com.kohere.auth.domain.RefreshTokenRepository;
import com.kohere.auth.domain.RequiredAgreementMissingException;
import com.kohere.auth.domain.SocialAccount;
import com.kohere.auth.domain.SocialAccountRepository;
import com.kohere.auth.domain.TermsAgreementRequiredException;
import com.kohere.auth.presentation.dto.BusinessVerifyRequest;
import com.kohere.auth.presentation.dto.EmailVerificationCodeRequest;
import com.kohere.auth.presentation.dto.EmailVerifyRequest;
import com.kohere.auth.presentation.dto.LandlordOnboardingRequest;
import com.kohere.auth.presentation.dto.LogoutRequest;
import com.kohere.auth.presentation.dto.OnboardingRequest;
import com.kohere.auth.presentation.dto.PhoneVerificationCodeRequest;
import com.kohere.auth.presentation.dto.PhoneVerifyRequest;
import com.kohere.auth.presentation.dto.ReissueRequest;
import com.kohere.auth.presentation.dto.SocialLoginRequest;
import com.kohere.auth.presentation.dto.TermsRequest;
import com.kohere.common.security.JwtTokenService;
import com.kohere.user.api.LandlordOnboardingProfile;
import com.kohere.user.api.OnboardingProfile;
import com.kohere.user.api.TermsAgreementView;
import com.kohere.user.api.UserAccountService;
import com.kohere.user.api.UserAccountView;
import com.kohere.user.api.UserProfileView;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link AuthService} 단위 테스트(Mockito) — 소셜 로그인 분기(신규/ACTIVE/미완료·status), 약관 동의(필수 검증), 이메일 인증 위임(약관
 * 동의 선행 게이트), 온보딩(약관 동의 → 이메일 검증 순서 강제), 재발급(항상 회전·재사용 탐지), 로그아웃 멱등. 도메인 포트·user 공개 API·이메일 인증 서비스를
 * 모킹한다.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

  @Mock private OidcTokenVerifier oidcTokenVerifier;
  @Mock private SocialAccountRepository socialAccountRepository;
  @Mock private RefreshTokenRepository refreshTokenRepository;
  @Mock private RefreshTokenHasher refreshTokenHasher;
  @Mock private JwtTokenService jwtTokenService;
  @Mock private UserAccountService userAccountService;
  @Mock private EmailVerificationService emailVerificationService;
  @Mock private PhoneVerificationService phoneVerificationService;
  @Mock private BusinessVerificationService businessVerificationService;
  @Mock private AppleAuthClient appleAuthClient;

  private AuthService authService;

  @BeforeEach
  void setUp() {
    AuthProperties authProperties = new AuthProperties();
    authProperties.setRefreshTtlSeconds(1209600);
    authProperties.setRefreshPepper("pepper");
    authService =
        new AuthService(
            oidcTokenVerifier,
            socialAccountRepository,
            refreshTokenRepository,
            refreshTokenHasher,
            jwtTokenService,
            userAccountService,
            emailVerificationService,
            phoneVerificationService,
            businessVerificationService,
            authProperties,
            appleAuthClient);
  }

  @Test
  void socialLogin_newUser_createsPendingAndIssuesOnboardingToken() {
    when(oidcTokenVerifier.verify(Provider.GOOGLE, "idtok"))
        .thenReturn(new OidcUser(Provider.GOOGLE, "sub-1", "a@example.com"));
    when(socialAccountRepository.findByProviderAndProviderUserId(Provider.GOOGLE, "sub-1"))
        .thenReturn(Optional.empty());
    when(userAccountService.createPendingUser()).thenReturn(10L);
    when(jwtTokenService.issueOnboardingToken(10L)).thenReturn("onboarding-token");
    when(jwtTokenService.onboardingTtlSeconds()).thenReturn(1800L);

    SocialLoginResponse response =
        authService.socialLogin(new SocialLoginRequest(Provider.GOOGLE, "idtok", null));

    assertThat(response.onboardingRequired()).isTrue();
    assertThat(response.status()).isEqualTo("PENDING");
    assertThat(response.accessToken()).isEqualTo("onboarding-token");
    assertThat(response.refreshToken()).isNull();
    assertThat(response.expiresIn()).isEqualTo(1800L);
    verify(socialAccountRepository).save(any(SocialAccount.class));
    verify(refreshTokenRepository, never()).save(any());
  }

  @Test
  void socialLogin_existingActiveUser_issuesAccessAndRefresh() {
    when(oidcTokenVerifier.verify(Provider.GOOGLE, "idtok"))
        .thenReturn(new OidcUser(Provider.GOOGLE, "sub-1", "a@example.com"));
    when(socialAccountRepository.findByProviderAndProviderUserId(Provider.GOOGLE, "sub-1"))
        .thenReturn(Optional.of(socialAccount(20L)));
    when(userAccountService.getAccount(20L)).thenReturn(new UserAccountView(20L, "ACTIVE"));
    when(jwtTokenService.issueAccessToken(20L)).thenReturn("access-token");
    when(jwtTokenService.accessTtlSeconds()).thenReturn(3600L);
    when(refreshTokenHasher.hash(any())).thenReturn("hash");

    SocialLoginResponse response =
        authService.socialLogin(new SocialLoginRequest(Provider.GOOGLE, "idtok", null));

    assertThat(response.onboardingRequired()).isFalse();
    assertThat(response.status()).isEqualTo("ACTIVE");
    assertThat(response.accessToken()).isEqualTo("access-token");
    assertThat(response.refreshToken()).isNotNull();
    assertThat(response.expiresIn()).isEqualTo(3600L);
    verify(refreshTokenRepository).save(any(RefreshToken.class));
    verify(userAccountService, never()).createPendingUser();
  }

  @Test
  void socialLogin_existingTermsAgreedUser_reissuesOnboardingTokenWithStatus() {
    when(oidcTokenVerifier.verify(Provider.GOOGLE, "idtok"))
        .thenReturn(new OidcUser(Provider.GOOGLE, "sub-1", "a@example.com"));
    when(socialAccountRepository.findByProviderAndProviderUserId(Provider.GOOGLE, "sub-1"))
        .thenReturn(Optional.of(socialAccount(30L)));
    when(userAccountService.getAccount(30L)).thenReturn(new UserAccountView(30L, "TERMS_AGREED"));
    when(jwtTokenService.issueOnboardingToken(30L)).thenReturn("onboarding-token");
    when(jwtTokenService.onboardingTtlSeconds()).thenReturn(1800L);

    SocialLoginResponse response =
        authService.socialLogin(new SocialLoginRequest(Provider.GOOGLE, "idtok", null));

    assertThat(response.onboardingRequired()).isTrue();
    assertThat(response.status()).isEqualTo("TERMS_AGREED");
    assertThat(response.refreshToken()).isNull();
    verify(userAccountService, never()).createPendingUser();
    verify(socialAccountRepository, never()).save(any());
  }

  @Test
  void socialLogin_apple_newUser_exchangesCodeVerifiesIdTokenAndStoresRefresh() {
    when(appleAuthClient.exchangeAuthorizationCode("apple-code"))
        .thenReturn(new AppleAuthClient.AppleTokens("apple-id-token", "apple-rt-1"));
    when(oidcTokenVerifier.verify(Provider.APPLE, "apple-id-token"))
        .thenReturn(new OidcUser(Provider.APPLE, "apple-sub", "a@privaterelay.appleid.com"));
    when(socialAccountRepository.findByProviderAndProviderUserId(Provider.APPLE, "apple-sub"))
        .thenReturn(Optional.empty());
    when(userAccountService.createPendingUser()).thenReturn(11L);
    when(jwtTokenService.issueOnboardingToken(11L)).thenReturn("onboarding-token");
    when(jwtTokenService.onboardingTtlSeconds()).thenReturn(1800L);

    SocialLoginResponse response =
        authService.socialLogin(new SocialLoginRequest(Provider.APPLE, null, "apple-code"));

    assertThat(response.onboardingRequired()).isTrue();
    assertThat(response.status()).isEqualTo("PENDING");
    // 교환받은 id_token을 동일 검증기로 재검증한다(교환 응답 맹신 금지, ADR-0031 #1)
    verify(appleAuthClient).exchangeAuthorizationCode("apple-code");
    verify(oidcTokenVerifier).verify(Provider.APPLE, "apple-id-token");
    ArgumentCaptor<SocialAccount> captor = ArgumentCaptor.forClass(SocialAccount.class);
    verify(socialAccountRepository).save(captor.capture());
    assertThat(captor.getValue().getProvider()).isEqualTo(Provider.APPLE);
    assertThat(captor.getValue().getAppleRefreshToken()).isEqualTo("apple-rt-1");
  }

  @Test
  void socialLogin_apple_existingActive_upsertsRefreshTokenWhenReturned() {
    when(appleAuthClient.exchangeAuthorizationCode("apple-code"))
        .thenReturn(new AppleAuthClient.AppleTokens("apple-id-token", "apple-rt-new"));
    when(oidcTokenVerifier.verify(Provider.APPLE, "apple-id-token"))
        .thenReturn(new OidcUser(Provider.APPLE, "apple-sub", "a@example.com"));
    when(socialAccountRepository.findByProviderAndProviderUserId(Provider.APPLE, "apple-sub"))
        .thenReturn(Optional.of(appleAccount(21L, "apple-rt-old")));
    when(userAccountService.getAccount(21L)).thenReturn(new UserAccountView(21L, "ACTIVE"));
    when(jwtTokenService.issueAccessToken(21L)).thenReturn("access-token");
    when(jwtTokenService.accessTtlSeconds()).thenReturn(3600L);
    when(refreshTokenHasher.hash(any())).thenReturn("hash");

    SocialLoginResponse response =
        authService.socialLogin(new SocialLoginRequest(Provider.APPLE, null, "apple-code"));

    assertThat(response.status()).isEqualTo("ACTIVE");
    ArgumentCaptor<SocialAccount> captor = ArgumentCaptor.forClass(SocialAccount.class);
    verify(socialAccountRepository).save(captor.capture());
    assertThat(captor.getValue().getAppleRefreshToken()).isEqualTo("apple-rt-new");
    verify(refreshTokenRepository).save(any(RefreshToken.class));
  }

  @Test
  void socialLogin_apple_reLoginWithoutRefreshToken_preservesStoredToken() {
    when(appleAuthClient.exchangeAuthorizationCode("apple-code"))
        .thenReturn(new AppleAuthClient.AppleTokens("apple-id-token", null));
    when(oidcTokenVerifier.verify(Provider.APPLE, "apple-id-token"))
        .thenReturn(new OidcUser(Provider.APPLE, "apple-sub", "a@example.com"));
    when(socialAccountRepository.findByProviderAndProviderUserId(Provider.APPLE, "apple-sub"))
        .thenReturn(Optional.of(appleAccount(22L, "apple-rt-existing")));
    when(userAccountService.getAccount(22L)).thenReturn(new UserAccountView(22L, "ACTIVE"));
    when(jwtTokenService.issueAccessToken(22L)).thenReturn("access-token");
    when(jwtTokenService.accessTtlSeconds()).thenReturn(3600L);
    when(refreshTokenHasher.hash(any())).thenReturn("hash");

    authService.socialLogin(new SocialLoginRequest(Provider.APPLE, null, "apple-code"));

    // refresh token이 새로 안 왔으면 매핑을 다시 저장하지 않는다 → 저장된 토큰 보존(ADR-0031 #4)
    verify(socialAccountRepository, never()).save(any(SocialAccount.class));
    verify(refreshTokenRepository).save(any(RefreshToken.class));
  }

  @Test
  void socialLogin_apple_missingAuthorizationCode_throwsMissingCredential() {
    assertThatThrownBy(
            () -> authService.socialLogin(new SocialLoginRequest(Provider.APPLE, null, null)))
        .isInstanceOf(MissingCredentialException.class);

    verify(appleAuthClient, never()).exchangeAuthorizationCode(any());
  }

  @Test
  void socialLogin_google_blankIdToken_throwsMissingCredential() {
    assertThatThrownBy(
            () -> authService.socialLogin(new SocialLoginRequest(Provider.GOOGLE, "  ", null)))
        .isInstanceOf(MissingCredentialException.class);

    verify(oidcTokenVerifier, never()).verify(any(), any());
  }

  @Test
  void agreeToTerms_recordsConsentAndReturnsTermsAgreed() {
    Instant agreedAt = Instant.parse("2026-06-17T00:00:00Z");
    when(userAccountService.agreeToTerms(40L, false))
        .thenReturn(new TermsAgreementView("TERMS_AGREED", true, true, false, agreedAt));

    TermsResponse response = authService.agreeToTerms(40L, new TermsRequest(true, true, false));

    assertThat(response.status()).isEqualTo("TERMS_AGREED");
    assertThat(response.termsOfServiceAgreed()).isTrue();
    assertThat(response.agreedAt()).isEqualTo(agreedAt);
    verify(userAccountService).agreeToTerms(40L, false);
  }

  @Test
  void agreeToTerms_missingRequiredAgreement_throwsAndDoesNotPersist() {
    assertThatThrownBy(() -> authService.agreeToTerms(40L, new TermsRequest(false, true, false)))
        .isInstanceOf(RequiredAgreementMissingException.class);

    verify(userAccountService, never())
        .agreeToTerms(anyLong(), org.mockito.ArgumentMatchers.anyBoolean());
  }

  @Test
  void sendEmailVerificationCode_delegatesAndMasksEmail() {
    when(userAccountService.getAccount(40L)).thenReturn(new UserAccountView(40L, "TERMS_AGREED"));
    when(emailVerificationService.sendCode(40L, "minh@example.com")).thenReturn(300L);

    EmailVerificationCodeResponse response =
        authService.sendEmailVerificationCode(
            40L, new EmailVerificationCodeRequest("minh@example.com"));

    assertThat(response.expiresIn()).isEqualTo(300L);
    assertThat(response.email()).isEqualTo("mi***@example.com");
    verify(emailVerificationService).sendCode(40L, "minh@example.com");
  }

  @Test
  void sendEmailVerificationCode_activeUser_throwsAlreadyCompletedAndDoesNotSend() {
    when(userAccountService.getAccount(40L)).thenReturn(new UserAccountView(40L, "ACTIVE"));

    assertThatThrownBy(
            () ->
                authService.sendEmailVerificationCode(
                    40L, new EmailVerificationCodeRequest("minh@example.com")))
        .isInstanceOf(OnboardingAlreadyCompletedException.class);

    verify(emailVerificationService, never()).sendCode(anyLong(), any());
  }

  @Test
  void sendEmailVerificationCode_termsNotAgreed_throwsTermsRequiredAndDoesNotSend() {
    when(userAccountService.getAccount(40L)).thenReturn(new UserAccountView(40L, "PENDING"));

    assertThatThrownBy(
            () ->
                authService.sendEmailVerificationCode(
                    40L, new EmailVerificationCodeRequest("minh@example.com")))
        .isInstanceOf(TermsAgreementRequiredException.class);

    verify(emailVerificationService, never()).sendCode(anyLong(), any());
  }

  @Test
  void verifyEmail_delegatesAndReturnsVerified() {
    EmailVerifyResponse response =
        authService.verifyEmail(40L, new EmailVerifyRequest("minh@example.com", "482915"));

    assertThat(response.verified()).isTrue();
    assertThat(response.email()).isEqualTo("mi***@example.com");
    verify(emailVerificationService).verify(40L, "minh@example.com", "482915");
  }

  @Test
  void onboarding_completesAndIssuesFullTokensWithProfile() {
    when(userAccountService.getAccount(40L)).thenReturn(new UserAccountView(40L, "TERMS_AGREED"));
    when(jwtTokenService.issueAccessToken(40L)).thenReturn("access-token");
    when(jwtTokenService.accessTtlSeconds()).thenReturn(3600L);
    when(refreshTokenHasher.hash(any())).thenReturn("hash");
    when(userAccountService.completeOnboarding(eq(40L), any(OnboardingProfile.class)))
        .thenReturn(profileView(40L));

    OnboardingResponse response = authService.onboarding(40L, onboardingRequest());

    assertThat(response.user()).isNotNull();
    assertThat(response.user().id()).isEqualTo(40L);
    assertThat(response.user().status()).isEqualTo("ACTIVE");
    assertThat(response.user().nickname()).isEqualTo("BraveOtter");
    assertThat(response.accessToken()).isEqualTo("access-token");
    assertThat(response.refreshToken()).isNotNull();
    verify(emailVerificationService).assertVerified(40L, "gil@example.com");
    verify(userAccountService).completeOnboarding(eq(40L), any(OnboardingProfile.class));
    verify(refreshTokenRepository).save(any(RefreshToken.class));
  }

  @Test
  void onboarding_emailNotVerified_throwsAndDoesNotComplete() {
    when(userAccountService.getAccount(40L)).thenReturn(new UserAccountView(40L, "TERMS_AGREED"));
    doThrow(new EmailNotVerifiedException())
        .when(emailVerificationService)
        .assertVerified(40L, "gil@example.com");

    assertThatThrownBy(() -> authService.onboarding(40L, onboardingRequest()))
        .isInstanceOf(EmailNotVerifiedException.class);

    verify(userAccountService, never()).completeOnboarding(anyLong(), any());
    verify(refreshTokenRepository, never()).save(any());
  }

  @Test
  void onboarding_termsNotAgreed_throwsTermsRequiredBeforeEmailCheck() {
    when(userAccountService.getAccount(40L)).thenReturn(new UserAccountView(40L, "PENDING"));

    assertThatThrownBy(() -> authService.onboarding(40L, onboardingRequest()))
        .isInstanceOf(TermsAgreementRequiredException.class);

    // 약관 미동의면 이메일 검증·온보딩 완료 전에 차단 — 이메일 인증 안내보다 약관 동의 안내가 먼저
    verify(emailVerificationService, never()).assertVerified(anyLong(), any());
    verify(userAccountService, never()).completeOnboarding(anyLong(), any());
    verify(refreshTokenRepository, never()).save(any());
  }

  @Test
  void reissue_rotatesActiveTokenAndIssuesNewTokens() {
    Instant now = Instant.now();
    RefreshToken active = RefreshToken.issue("hash", 50L, now, now.plusSeconds(1000));
    when(refreshTokenHasher.hash("raw-refresh")).thenReturn("hash");
    when(refreshTokenRepository.findByTokenHash("hash")).thenReturn(Optional.of(active));
    when(jwtTokenService.issueAccessToken(50L)).thenReturn("new-access");
    when(jwtTokenService.accessTtlSeconds()).thenReturn(3600L);

    TokenResponse response = authService.reissue(new ReissueRequest("raw-refresh"));

    assertThat(response.accessToken()).isEqualTo("new-access");
    assertThat(response.refreshToken()).isNotNull();
    // 제출 토큰 ROTATED 저장 + 새 ACTIVE 저장 → save 2회
    verify(refreshTokenRepository, org.mockito.Mockito.times(2)).save(any(RefreshToken.class));
  }

  @Test
  void reissue_reuseOfRotatedToken_revokesAllAndRejects() {
    Instant now = Instant.now();
    RefreshToken rotated = RefreshToken.issue("hash", 60L, now, now.plusSeconds(1000)).rotate();
    when(refreshTokenHasher.hash("raw-refresh")).thenReturn("hash");
    when(refreshTokenRepository.findByTokenHash("hash")).thenReturn(Optional.of(rotated));

    assertThatThrownBy(() -> authService.reissue(new ReissueRequest("raw-refresh")))
        .isInstanceOf(InvalidRefreshTokenException.class);

    verify(refreshTokenRepository).revokeAllByUserId(60L);
  }

  @Test
  void reissue_revokedToken_rejectsWithoutBulkRevoke() {
    Instant now = Instant.now();
    RefreshToken revoked = RefreshToken.issue("hash", 65L, now, now.plusSeconds(1000)).revoke();
    when(refreshTokenHasher.hash("raw-refresh")).thenReturn("hash");
    when(refreshTokenRepository.findByTokenHash("hash")).thenReturn(Optional.of(revoked));

    assertThatThrownBy(() -> authService.reissue(new ReissueRequest("raw-refresh")))
        .isInstanceOf(InvalidRefreshTokenException.class);

    verify(refreshTokenRepository, never()).revokeAllByUserId(anyLong());
  }

  @Test
  void reissue_unknownToken_rejects() {
    when(refreshTokenHasher.hash("raw-refresh")).thenReturn("hash");
    when(refreshTokenRepository.findByTokenHash("hash")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> authService.reissue(new ReissueRequest("raw-refresh")))
        .isInstanceOf(InvalidRefreshTokenException.class);
  }

  @Test
  void reissue_expiredToken_rejects() {
    Instant now = Instant.now();
    RefreshToken expired =
        RefreshToken.issue("hash", 70L, now.minusSeconds(2000), now.minusSeconds(1000));
    when(refreshTokenHasher.hash("raw-refresh")).thenReturn("hash");
    when(refreshTokenRepository.findByTokenHash("hash")).thenReturn(Optional.of(expired));

    assertThatThrownBy(() -> authService.reissue(new ReissueRequest("raw-refresh")))
        .isInstanceOf(InvalidRefreshTokenException.class);
  }

  @Test
  void logout_revokesToken() {
    Instant now = Instant.now();
    RefreshToken active = RefreshToken.issue("hash", 80L, now, now.plusSeconds(1000));
    when(refreshTokenHasher.hash("raw-refresh")).thenReturn("hash");
    when(refreshTokenRepository.findByTokenHash("hash")).thenReturn(Optional.of(active));

    authService.logout(new LogoutRequest("raw-refresh"));

    verify(refreshTokenRepository).save(any(RefreshToken.class));
  }

  @Test
  void logout_isIdempotentWhenTokenAbsent() {
    when(refreshTokenHasher.hash("raw-refresh")).thenReturn("hash");
    when(refreshTokenRepository.findByTokenHash("hash")).thenReturn(Optional.empty());

    authService.logout(new LogoutRequest("raw-refresh"));

    verify(refreshTokenRepository, never()).save(any());
  }

  // ===== 임대인 트랙: 연락처 SMS 인증 · 사업자번호 검증 · 임대인 온보딩(ADR-0034) =====

  @Test
  void sendPhoneVerificationCode_delegatesAndMasksPhone() {
    when(userAccountService.getAccount(40L)).thenReturn(new UserAccountView(40L, "TERMS_AGREED"));
    when(phoneVerificationService.sendCode(40L, "01012345678")).thenReturn(300L);

    PhoneVerificationCodeResponse response =
        authService.sendPhoneVerificationCode(40L, new PhoneVerificationCodeRequest("01012345678"));

    assertThat(response.expiresIn()).isEqualTo(300L);
    assertThat(response.phoneNumber()).isEqualTo("010-****-5678");
    verify(phoneVerificationService).sendCode(40L, "01012345678");
  }

  @Test
  void sendPhoneVerificationCode_activeUser_allowedForProfileChange() {
    // US-1-5: 정식 회원(ACTIVE)도 프로필 연락처 변경을 위해 인증번호를 받을 수 있다(온보딩 전용 아님, ADR-0034 §6·§8).
    when(userAccountService.getAccount(40L)).thenReturn(new UserAccountView(40L, "ACTIVE"));
    when(phoneVerificationService.sendCode(40L, "01012345678")).thenReturn(300L);

    PhoneVerificationCodeResponse response =
        authService.sendPhoneVerificationCode(40L, new PhoneVerificationCodeRequest("01012345678"));

    assertThat(response.phoneNumber()).isEqualTo("010-****-5678");
    verify(phoneVerificationService).sendCode(40L, "01012345678");
  }

  @Test
  void sendPhoneVerificationCode_termsNotAgreed_throwsTermsRequiredAndDoesNotSend() {
    when(userAccountService.getAccount(40L)).thenReturn(new UserAccountView(40L, "PENDING"));

    assertThatThrownBy(
            () ->
                authService.sendPhoneVerificationCode(
                    40L, new PhoneVerificationCodeRequest("01012345678")))
        .isInstanceOf(TermsAgreementRequiredException.class);

    verify(phoneVerificationService, never()).sendCode(anyLong(), any());
  }

  @Test
  void verifyPhone_delegatesAndReturnsVerified() {
    PhoneVerifyResponse response =
        authService.verifyPhone(40L, new PhoneVerifyRequest("01012345678", "482915"));

    assertThat(response.verified()).isTrue();
    assertThat(response.phoneNumber()).isEqualTo("010-****-5678");
    verify(phoneVerificationService).verify(40L, "01012345678", "482915");
  }

  @Test
  void verifyBusiness_landlord_delegatesAndMasksNumber() {
    when(userAccountService.getUserType(40L)).thenReturn("LANDLORD");

    BusinessVerifyResponse response =
        authService.verifyBusiness(40L, new BusinessVerifyRequest("1234567890"));

    assertThat(response.verified()).isTrue();
    assertThat(response.businessRegistrationNumber()).isEqualTo("****567890");
    verify(businessVerificationService).verify("1234567890");
  }

  @Test
  void verifyBusiness_notLandlord_throwsForbiddenAndDoesNotVerify() {
    when(userAccountService.getUserType(40L)).thenReturn("TENANT");

    assertThatThrownBy(
            () -> authService.verifyBusiness(40L, new BusinessVerifyRequest("1234567890")))
        .isInstanceOf(LandlordOnlyException.class);

    verify(businessVerificationService, never()).verify(any());
  }

  @Test
  void landlordOnboarding_completesAndIssuesFullTokensWithLandlordProfile() {
    when(userAccountService.getAccount(40L)).thenReturn(new UserAccountView(40L, "TERMS_AGREED"));
    when(userAccountService.completeLandlordOnboarding(
            eq(40L), any(LandlordOnboardingProfile.class)))
        .thenReturn(landlordProfileView(40L));
    when(jwtTokenService.issueAccessToken(40L)).thenReturn("access-token");
    when(jwtTokenService.accessTtlSeconds()).thenReturn(3600L);
    when(refreshTokenHasher.hash(any())).thenReturn("hash");

    OnboardingResponse response = authService.landlordOnboarding(40L, landlordOnboardingRequest());

    assertThat(response.user()).isNotNull();
    assertThat(response.user().id()).isEqualTo(40L);
    assertThat(response.user().status()).isEqualTo("ACTIVE");
    assertThat(response.user().userType()).isEqualTo("LANDLORD");
    assertThat(response.accessToken()).isEqualTo("access-token");
    assertThat(response.refreshToken()).isNotNull();
    // 게이트 통과 순서: 약관 → 연락처(사업자번호는 온보딩에서 수집하지 않음)
    verify(phoneVerificationService).assertVerified(40L, "01012345678");
    ArgumentCaptor<LandlordOnboardingProfile> captor =
        ArgumentCaptor.forClass(LandlordOnboardingProfile.class);
    verify(userAccountService).completeLandlordOnboarding(eq(40L), captor.capture());
    assertThat(captor.getValue().name()).isEqualTo("Kim Imdae");
    assertThat(captor.getValue().phoneNumber()).isEqualTo("01012345678");
    assertThat(captor.getValue().birthDate()).isEqualTo(LocalDate.of(1990, 1, 1));
    verify(refreshTokenRepository).save(any(RefreshToken.class));
  }

  @Test
  void landlordOnboarding_phoneNotVerified_throwsAndDoesNotComplete() {
    when(userAccountService.getAccount(40L)).thenReturn(new UserAccountView(40L, "TERMS_AGREED"));
    doThrow(new PhoneNotVerifiedException())
        .when(phoneVerificationService)
        .assertVerified(40L, "01012345678");

    assertThatThrownBy(() -> authService.landlordOnboarding(40L, landlordOnboardingRequest()))
        .isInstanceOf(PhoneNotVerifiedException.class);

    // 연락처 미인증이면 온보딩 완료 전에 차단
    verify(userAccountService, never()).completeLandlordOnboarding(anyLong(), any());
    verify(refreshTokenRepository, never()).save(any());
  }

  @Test
  void landlordOnboarding_termsNotAgreed_throwsTermsRequiredBeforeVerificationChecks() {
    when(userAccountService.getAccount(40L)).thenReturn(new UserAccountView(40L, "PENDING"));

    assertThatThrownBy(() -> authService.landlordOnboarding(40L, landlordOnboardingRequest()))
        .isInstanceOf(TermsAgreementRequiredException.class);

    // 약관 미동의면 연락처 검사보다 약관 동의 안내가 먼저
    verify(phoneVerificationService, never()).assertVerified(anyLong(), any());
    verify(userAccountService, never()).completeLandlordOnboarding(anyLong(), any());
    verify(refreshTokenRepository, never()).save(any());
  }

  private static SocialAccount socialAccount(long userId) {
    return SocialAccount.builder()
        .id(1L)
        .provider(Provider.GOOGLE)
        .providerUserId("sub-1")
        .email("a@example.com")
        .userId(userId)
        .linkedAt(Instant.now())
        .build();
  }

  private static SocialAccount appleAccount(long userId, String appleRefreshToken) {
    return SocialAccount.builder()
        .id(2L)
        .provider(Provider.APPLE)
        .providerUserId("apple-sub")
        .email("a@example.com")
        .userId(userId)
        .linkedAt(Instant.now())
        .appleRefreshToken(appleRefreshToken)
        .build();
  }

  private static UserProfileView profileView(long id) {
    return new UserProfileView(
        id,
        "Gil",
        "Hong",
        null,
        "BraveOtter",
        "MALE",
        LocalDate.of(1990, 1, 1),
        "KR",
        "South Korea",
        "https://flagcdn.com/kr.svg",
        null,
        "UNDERGRADUATE_STUDENT",
        "gil@example.com",
        "SHORT_TERM_VISIT",
        null,
        "TENANT",
        "ACTIVE",
        false,
        Instant.now());
  }

  private static OnboardingRequest onboardingRequest() {
    return new OnboardingRequest(
        "Gil",
        "Hong",
        "MALE",
        LocalDate.of(1990, 1, 1),
        "KR",
        "UNDERGRADUATE_STUDENT",
        "gil@example.com",
        "SHORT_TERM_VISIT",
        null);
  }

  /**
   * 임대인 온보딩 응답 프로필 — 성별·직업·비자·이메일 미수집(null), 국적(`KR`)·표시 언어(`ko`)는 서버가 고정 부여, 전체 이름은 {@code name}에,
   * 마스킹된 연락처를 {@code phoneNumber}에, userType=LANDLORD.
   */
  private static UserProfileView landlordProfileView(long id) {
    return new UserProfileView(
        id,
        null,
        null,
        "Kim Imdae",
        "CalmFox",
        null,
        LocalDate.of(1990, 1, 1),
        "KR",
        "South Korea",
        "https://flagcdn.com/kr.svg",
        "ko",
        null,
        null,
        null,
        "010-****-5678",
        "LANDLORD",
        "ACTIVE",
        false,
        Instant.now());
  }

  private static LandlordOnboardingRequest landlordOnboardingRequest() {
    return new LandlordOnboardingRequest("Kim Imdae", "01012345678", LocalDate.of(1990, 1, 1));
  }
}
