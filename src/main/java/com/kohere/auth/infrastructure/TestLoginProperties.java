package com.kohere.auth.infrastructure;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * dev·local 전용 테스트 마스터 로그인 우회 설정(app.auth.test-login). 실제 Apple/Google OIDC 검증 없이 미리 시드된 마스터 계정으로
 * 반복 로그인하기 위한 개발 편의 기능이며 <b>운영에서는 절대 활성화하지 않는다</b>.
 *
 * <p>이중 게이트로 보호한다 — {@link TestLoginOidcTokenVerifier}는 {@code @Profile({"local","dev"})} + {@code
 * enabled=true}일 때만 빈으로 등록된다(prod 프로파일엔 존재조차 하지 않음). {@code secret}은 우회 요청이 실어 보내는 값과 대조하는 공유
 * 시크릿으로, dev는 SSM→refresh-env(.env)로 주입하고 로컬만 개발용 폴백을 쓴다(JWT_SECRET과 동일 방식, ADR-0023/0024).
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.auth.test-login")
public class TestLoginProperties {

  /** 우회 활성화(미설정 시 비활성). true여도 {@code @Profile}가 local·dev로 한정한다. */
  private boolean enabled = false;

  /** 우회 요청({@code idToken=master:<role>:<secret>})의 시크릿과 대조할 공유 시크릿. 비어 있으면 우회 불가. */
  private String secret;
}
