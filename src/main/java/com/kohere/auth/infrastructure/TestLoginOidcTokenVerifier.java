package com.kohere.auth.infrastructure;

import com.kohere.auth.domain.InvalidSocialTokenException;
import com.kohere.auth.domain.OidcTokenVerifier;
import com.kohere.auth.domain.OidcUser;
import com.kohere.auth.domain.Provider;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * dev·local 전용 테스트 로그인 우회 {@link OidcTokenVerifier} 래퍼. {@code idToken}이 {@code
 * master:<role>:<secret>} 형태이고 공유 시크릿({@link TestLoginProperties#getSecret()})이 일치하면 실제 OIDC 검증 없이
 * 시드된 마스터 계정({@link TestMasterAccount})의 신원을 반환하고, 그 외 모든 토큰은 실제 검증기({@link
 * OidcTokenVerifierImpl})에 위임한다 — 진짜 Google/Apple 로그인은 그대로 동작한다.
 *
 * <p>이중 게이트({@code @Profile({"local","dev"})} + {@code app.auth.test-login.enabled=true})로만 등록되며,
 * prod 프로파일엔 이 빈이 존재하지 않아 백도어가 성립하지 않는다. {@code @Primary}로 {@link AuthService}가 이 래퍼를 주입받게 하고, 래퍼는
 * 실제 구현을 concrete 타입으로 주입해 위임한다(순환 없음). 우회는 시드된 마스터 계정 + 시크릿 보유자로만 한정된다.
 */
@Component
@Primary
@Profile({"local", "dev"})
@ConditionalOnProperty(prefix = "app.auth.test-login", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
class TestLoginOidcTokenVerifier implements OidcTokenVerifier {

  private static final String MASTER_PREFIX = "master:";

  private final OidcTokenVerifierImpl delegate;
  private final TestLoginProperties properties;

  @Override
  public OidcUser verify(Provider provider, String idToken) {
    if (idToken != null && idToken.startsWith(MASTER_PREFIX)) {
      return resolveMaster(idToken);
    }
    return delegate.verify(provider, idToken);
  }

  /**
   * {@code master:<role>:<secret>}를 파싱해 시드된 마스터 신원을 반환한다. 형식 오류·시크릿 불일치·미정의 역할은 실제 검증 실패와 동일하게
   * {@link InvalidSocialTokenException}(401)으로 처리해 우회 시도를 노출하지 않는다.
   */
  private OidcUser resolveMaster(String idToken) {
    String[] parts = idToken.split(":", 3);
    if (parts.length < 3) {
      throw new InvalidSocialTokenException();
    }
    String role = parts[1];
    String presentedSecret = parts[2];
    if (!secretMatches(presentedSecret)) {
      throw new InvalidSocialTokenException();
    }
    TestMasterAccount account =
        TestMasterAccount.byRole(role).orElseThrow(InvalidSocialTokenException::new);
    return new OidcUser(account.provider(), account.subject(), account.email());
  }

  /** 상수 시간 비교로 제출 시크릿과 설정 시크릿을 대조한다. 설정 시크릿이 비어 있으면(미주입) 항상 불일치. */
  private boolean secretMatches(String presentedSecret) {
    String expected = properties.getSecret();
    if (!StringUtils.hasText(expected)) {
      return false;
    }
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8),
        presentedSecret.getBytes(StandardCharsets.UTF_8));
  }
}
