package com.kohere.auth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kohere.auth.domain.SocialAccount;
import com.kohere.auth.domain.SocialAccountRepository;
import com.kohere.user.api.LandlordOnboardingProfile;
import com.kohere.user.api.OnboardingProfile;
import com.kohere.user.api.UserAccountService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link MasterAccountSeedRunner} 단위 테스트(Mockito). 미시드 시 세입자·임대인 마스터를 도메인 흐름으로 ACTIVE까지 만들고
 * social_accounts 매핑을 생성하며, 이미 시드된 경우 생성을 건너뛰는(멱등) 것을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class MasterAccountSeedRunnerTest {

  @Mock private UserAccountService userAccountService;
  @Mock private SocialAccountRepository socialAccountRepository;

  private MasterAccountSeedRunner runner() {
    return new MasterAccountSeedRunner(userAccountService, socialAccountRepository);
  }

  @Test
  void run_whenNotSeeded_createsActiveTenantAndLandlordWithSocialMapping() {
    when(socialAccountRepository.findByProviderAndProviderUserId(any(), any()))
        .thenReturn(Optional.empty());
    when(userAccountService.createPendingUser()).thenReturn(1L, 2L);

    runner().run(null);

    verify(userAccountService, times(2)).createPendingUser();
    verify(userAccountService).agreeToTerms(1L, true);
    verify(userAccountService).completeOnboarding(eq(1L), any(OnboardingProfile.class));
    verify(userAccountService).agreeToTerms(2L, true);
    verify(userAccountService)
        .completeLandlordOnboarding(eq(2L), any(LandlordOnboardingProfile.class));

    ArgumentCaptor<SocialAccount> captor = ArgumentCaptor.forClass(SocialAccount.class);
    verify(socialAccountRepository, times(2)).save(captor.capture());
    assertThat(captor.getAllValues())
        .extracting(SocialAccount::getProviderUserId)
        .containsExactly("test-master-tenant", "test-master-landlord");
  }

  @Test
  void run_whenAlreadySeeded_isIdempotent_skipsCreation() {
    when(socialAccountRepository.findByProviderAndProviderUserId(any(), any()))
        .thenReturn(Optional.of(SocialAccount.builder().build()));

    runner().run(null);

    verify(userAccountService, never()).createPendingUser();
    verify(socialAccountRepository, never()).save(any());
  }
}
