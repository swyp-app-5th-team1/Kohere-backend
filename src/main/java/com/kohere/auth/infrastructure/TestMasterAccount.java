package com.kohere.auth.infrastructure;

import com.kohere.auth.domain.Provider;
import java.util.Optional;

/**
 * dev·local 테스트 마스터 계정 카탈로그(고정 신원). {@link MasterAccountSeedRunner}가 시드하는 계정과 {@link
 * TestLoginOidcTokenVerifier}가 우회 로그인 시 반환하는 신원이 반드시 같은 값을 쓰도록 단일 출처로 둔다.
 *
 * <p>{@code role}은 우회 요청 {@code idToken=master:<role>:<secret>}의 역할 토큰이고, {@code (provider,
 * subject)}는 {@code social_accounts}의 등치 조회 키다. subject는 실제 Google {@code sub}(숫자)와 절대 겹치지 않도록 접두사를
 * 붙인 상수를 쓴다.
 */
enum TestMasterAccount {
  /** 세입자 마스터(온보딩 프로필은 시더가 채운다). */
  TENANT("tenant", Provider.GOOGLE, "test-master-tenant", "master-tenant@kohere.test"),
  /** 임대인 마스터(name·연락처는 시더가 채운다). */
  LANDLORD("landlord", Provider.GOOGLE, "test-master-landlord", "master-landlord@kohere.test");

  private final String role;
  private final Provider provider;
  private final String subject;
  private final String email;

  TestMasterAccount(String role, Provider provider, String subject, String email) {
    this.role = role;
    this.provider = provider;
    this.subject = subject;
    this.email = email;
  }

  String role() {
    return role;
  }

  Provider provider() {
    return provider;
  }

  String subject() {
    return subject;
  }

  String email() {
    return email;
  }

  /** 우회 요청의 역할 토큰({@code tenant}·{@code landlord})으로 마스터 계정을 찾는다. 미정의면 빈 Optional. */
  static Optional<TestMasterAccount> byRole(String role) {
    for (TestMasterAccount account : values()) {
      if (account.role.equals(role)) {
        return Optional.of(account);
      }
    }
    return Optional.empty();
  }
}
