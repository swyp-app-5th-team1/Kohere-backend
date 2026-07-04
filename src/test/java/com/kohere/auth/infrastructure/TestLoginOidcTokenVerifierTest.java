package com.kohere.auth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.kohere.auth.domain.InvalidSocialTokenException;
import com.kohere.auth.domain.OidcUser;
import com.kohere.auth.domain.Provider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link TestLoginOidcTokenVerifier} 단위 테스트(Mockito). {@code master:<role>:<secret>} 우회는 시크릿 일치 시
 * 시드된 마스터 신원을 위임 없이 반환하고, 형식·시크릿·역할 오류는 401로 막으며, 그 외 토큰은 실제 검증기에 위임함을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class TestLoginOidcTokenVerifierTest {

  private static final String SECRET = "s3cr3t-abc";

  @Mock private OidcTokenVerifierImpl delegate;
  private final TestLoginProperties properties = new TestLoginProperties();

  private TestLoginOidcTokenVerifier verifier;

  @BeforeEach
  void setUp() {
    properties.setEnabled(true);
    properties.setSecret(SECRET);
    verifier = new TestLoginOidcTokenVerifier(delegate, properties);
  }

  @Test
  void masterTenant_withCorrectSecret_returnsSeededIdentity_withoutDelegating() {
    OidcUser user = verifier.verify(Provider.GOOGLE, "master:tenant:" + SECRET);

    assertThat(user.provider()).isEqualTo(Provider.GOOGLE);
    assertThat(user.subject()).isEqualTo("test-master-tenant");
    verifyNoInteractions(delegate);
  }

  @Test
  void masterLandlord_withCorrectSecret_returnsSeededIdentity() {
    OidcUser user = verifier.verify(Provider.GOOGLE, "master:landlord:" + SECRET);

    assertThat(user.subject()).isEqualTo("test-master-landlord");
    verifyNoInteractions(delegate);
  }

  @Test
  void masterToken_withWrongSecret_throwsWithoutDelegating() {
    assertThatThrownBy(() -> verifier.verify(Provider.GOOGLE, "master:tenant:wrong"))
        .isInstanceOf(InvalidSocialTokenException.class);
    verifyNoInteractions(delegate);
  }

  @Test
  void masterToken_withUnknownRole_throws() {
    assertThatThrownBy(() -> verifier.verify(Provider.GOOGLE, "master:admin:" + SECRET))
        .isInstanceOf(InvalidSocialTokenException.class);
  }

  @Test
  void masterToken_whenSecretNotConfigured_throws() {
    properties.setSecret("");

    assertThatThrownBy(() -> verifier.verify(Provider.GOOGLE, "master:tenant:"))
        .isInstanceOf(InvalidSocialTokenException.class);
    verifyNoInteractions(delegate);
  }

  @Test
  void nonMasterToken_delegatesToRealVerifier() {
    OidcUser real = new OidcUser(Provider.GOOGLE, "google-sub-123", "real@example.com");
    when(delegate.verify(Provider.GOOGLE, "eyJreal.token.value")).thenReturn(real);

    OidcUser user = verifier.verify(Provider.GOOGLE, "eyJreal.token.value");

    assertThat(user).isEqualTo(real);
  }
}
