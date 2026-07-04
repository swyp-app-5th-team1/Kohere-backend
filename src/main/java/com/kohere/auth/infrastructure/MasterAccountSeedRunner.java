package com.kohere.auth.infrastructure;

import com.kohere.auth.domain.SocialAccount;
import com.kohere.auth.domain.SocialAccountRepository;
import com.kohere.user.api.LandlordOnboardingProfile;
import com.kohere.user.api.OnboardingProfile;
import com.kohere.user.api.UserAccountService;
import java.time.Instant;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * dev·local 전용 테스트 마스터 계정 시더. 기동 시 세입자·임대인 마스터 계정을 도메인 흐름(생성→약관 동의→온보딩)으로 <b>ACTIVE까지</b> 시드하고
 * {@code social_accounts} 매핑을 만들어, {@link TestLoginOidcTokenVerifier} 우회 로그인이 이 계정으로 토큰을 발급받게 한다.
 * 인증 게이트(이메일·연락처)는 auth 응용 계층에만 있으므로 user 공개 API를 직접 호출하면 자연히 우회되며, Redis 인증 마커도 불필요하다.
 *
 * <p>{@code (provider, providerUserId)} 매핑 존재 여부로 멱등 처리한다 — 이미 시드됐으면 건너뛴다. 프로필값은 유효한 enum·국가 코드(V4
 * 참조 테이블) 상수를 쓰고 닉네임은 시스템이 자동 배정한다. 테스트 로그인과 단일 토글을 공유한다 — {@code @Profile({"local","dev"})} +
 * {@code app.auth.test-login.enabled=true}로만 등록되어(시드=계정 생성, 검증기=우회 로그인이 한 스위치로 켜짐) prod엔 존재하지 않는다.
 */
@Component
@Profile({"local", "dev"})
@ConditionalOnProperty(prefix = "app.auth.test-login", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
class MasterAccountSeedRunner implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(MasterAccountSeedRunner.class);

  private final UserAccountService userAccountService;
  private final SocialAccountRepository socialAccountRepository;

  @Override
  public void run(ApplicationArguments args) {
    seedTenant();
    seedLandlord();
  }

  private void seedTenant() {
    TestMasterAccount account = TestMasterAccount.TENANT;
    if (alreadySeeded(account)) {
      return;
    }
    long userId = userAccountService.createPendingUser();
    userAccountService.agreeToTerms(userId, true);
    userAccountService.completeOnboarding(
        userId,
        new OnboardingProfile(
            "Master",
            "Tenant",
            "MALE",
            LocalDate.of(1990, 1, 1),
            "US",
            "DEVELOPER",
            account.email(),
            "STUDY_D-2"));
    link(account, userId);
    log.warn("[TEST SEED] 세입자 마스터 계정 시드 완료 (userId={}, subject={}).", userId, account.subject());
  }

  private void seedLandlord() {
    TestMasterAccount account = TestMasterAccount.LANDLORD;
    if (alreadySeeded(account)) {
      return;
    }
    long userId = userAccountService.createPendingUser();
    userAccountService.agreeToTerms(userId, true);
    userAccountService.completeLandlordOnboarding(
        userId, new LandlordOnboardingProfile("Master Landlord", "01000000000"));
    link(account, userId);
    log.warn("[TEST SEED] 임대인 마스터 계정 시드 완료 (userId={}, subject={}).", userId, account.subject());
  }

  private boolean alreadySeeded(TestMasterAccount account) {
    return socialAccountRepository
        .findByProviderAndProviderUserId(account.provider(), account.subject())
        .isPresent();
  }

  private void link(TestMasterAccount account, long userId) {
    socialAccountRepository.save(
        SocialAccount.builder()
            .provider(account.provider())
            .providerUserId(account.subject())
            .email(account.email())
            .userId(userId)
            .linkedAt(Instant.now())
            .build());
  }
}
