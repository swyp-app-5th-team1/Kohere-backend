package com.kohere.auth.application;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 메일 발신 주소(app.email.from). 키는 {@link EmailVerificationProperties}와 같은 트리에 있지만 <b>정책값이 아니라 발신 채널의
 * 속성</b>이라 따로 바인딩한다 — 같은 트리를 두 클래스가 나눠 갖는 것은 {@code app.auth.web}을 {@link AuthProperties}와 {@code
 * RefreshCookieProperties}가 나눠 갖는 것과 같은 형태다(미지정 필드는 무시되므로 충돌하지 않는다).
 *
 * <p><b>왜 {@code EmailVerificationProperties}에서 떼어냈는가</b> — 발신 주소를 쓰는 곳이 인증번호 메일 하나였을 때는 그 클래스에 있어도
 * 이름이 맞았다. 비밀번호 재설정 링크 메일(#272)이 더해지면서 <b>두 발송기가 같은 주소를 써야</b> 하는데, 재설정 발송기가 "이메일 인증 정책값" 클래스를
 * 주입받으면 클래스 이름이 거짓말이 된다. 그렇다고 각자 들면 값이 갈릴 수 있고, 갈린 순간 <b>같은 서비스가 두 주소로 메일을 보낸다</b> — 사용자는 둘 중 하나를
 * 사칭으로 의심한다.
 *
 * <p>yml 키는 그대로라 설정·인프라 배선은 바뀌지 않는다.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.email")
public class MailSenderProperties {

  /**
   * 발신 이메일 주소. <b>실제 SMTP 인증 계정과 같은 도메인이어야 한다</b> — 다르면 DKIM 서명 도메인·SPF 검사 도메인이 발신 도메인과 정렬되지 않아
   * DMARC를 통과할 수 없고, 그건 DNS 설정으로 고칠 수 있는 문제가 아니다(발송 provider를 바꾸거나 발신 주소를 인증 계정에 맞춰야 한다). 링크가 든 메일은
   * 인증번호 텍스트 메일보다 스팸 판정에 훨씬 민감하므로 이 정렬이 재설정 기능의 성립 조건이다.
   */
  private String from = "noreply@kohere.app";
}
