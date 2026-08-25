package com.kohere.auth.application;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 이메일 인증 정책값. 인증번호 길이·만료(TTL)·검증 시도 상한·재발송 간격·검증 완료 마커 TTL. 모두 잠정 기본값이며 운영 확정 필요(문서 §6).
 *
 * <p>발신 주소({@code app.email.from})는 여기 있지 않다 — 비밀번호 재설정 링크 메일(#272)과 <b>같은 값을 써야</b> 해서 {@link
 * MailSenderProperties}로 떼어냈다. 같은 키 트리를 두 클래스가 나눠 바인딩한다.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.email")
public class EmailVerificationProperties {

  /** 인증번호 자릿수. */
  private int codeLength = 6;

  /** 인증번호 만료(초). */
  private long codeTtlSeconds = 300;

  /** 검증 완료 마커 보존(초) — 온보딩 토큰 만료 정도. */
  private long verifiedTtlSeconds = 1800;

  /** 검증 시도 상한(초과 시 429). */
  private int maxAttempts = 5;

  /** 재발송 최소 간격(초, 미달 시 429). */
  private long resendIntervalSeconds = 60;
}
