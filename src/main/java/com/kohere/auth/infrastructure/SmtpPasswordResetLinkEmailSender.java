package com.kohere.auth.infrastructure;

import com.kohere.auth.application.AuthProperties;
import com.kohere.auth.application.MailSenderProperties;
import com.kohere.auth.domain.EmailDispatchException;
import com.kohere.auth.domain.PasswordResetLinkEmailSender;
import lombok.RequiredArgsConstructor;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * 재설정 링크 메일 발송 어댑터(SMTP). {@link JavaMailSender}로 <b>동기</b> 발송하고, provider 장애·타임아웃은 {@link
 * EmailDispatchException}(502)으로 변환한다 — 응용 계층의 send-then-store가 성립하려면 실패가 반드시 예외로 올라와야 한다.
 *
 * <p><b>인증번호 발송기({@link SmtpVerificationEmailSender})와 채널만 같고 계층이 다르다.</b> 저쪽 계층에는 {@code
 * FixedCodeEmailVerificationCodeIssuer}가 발급기 레벨에서 끼어 있어 심사 계정의 메일 발송을 통째로 가로채는데, 링크 메일을 그 위에 얹으면
 * dev 심사 기간에 <b>링크가 조용히 사라지고 응답은 200</b>이 된다. 그래서 포트를 따로 두었다({@link PasswordResetLinkEmailSender}
 * 참조). 발신 주소만 {@code app.email.from} 하나를 공유한다.
 *
 * <p><b>본문은 {@code SimpleMailMessage} 평문이다.</b> HTML 메일은 링크를 표시 문구 뒤에 숨길 수 있어 오히려 피싱과 구분이 어렵고, 저장소에
 * 템플릿 엔진·다국어 메일 자산이 아직 없다. 언어는 한국어 하나다 — 임대인 웹은 한국어 고정 트랙이다.
 *
 * <p><b>본문에 회신 불가 안내와 유효 시간을 넣는다.</b> 발신 주소는 수신함이 없어 답장이 아무 데도 닿지 않는데, 그 사실을 적어 두지 않으면 링크를 못 받은
 * 사용자가 답장으로 도움을 요청하고 <b>아무 응답도 받지 못한다</b>. 유효 시간은 "나중에 열어야지" 하고 닫는 사용자를 막는다.
 */
@Component
@RequiredArgsConstructor
public class SmtpPasswordResetLinkEmailSender implements PasswordResetLinkEmailSender {

  private static final int SECONDS_PER_MINUTE = 60;

  private final JavaMailSender mailSender;
  private final MailSenderProperties mailSenderProperties;
  private final AuthProperties authProperties;
  private final PasswordResetLinks passwordResetLinks;

  /**
   * 링크 메일 발송. 링크 조립은 {@link PasswordResetLinks}에 맡긴다 — base URL을 요청 헤더가 아니라 설정에서만 얻는 규칙이 한 곳에 있어야
   * 한다.
   *
   * <p><b>실패는 삼키지 않는다.</b> 발송 실패를 로그만 남기고 정상 반환하면 사용자는 "메일을 보냈다"는 화면을 보고 오지 않는 메일을 기다린다 — 그 화면이 곧
   * 거짓말이다. 502로 올려 클라이언트가 재시도하게 한다(§1-8).
   */
  @Override
  public void send(String to, String rawToken) {
    long ttlMinutes =
        authProperties.getWeb().getPasswordReset().getTokenTtlSeconds() / SECONDS_PER_MINUTE;
    SimpleMailMessage message = new SimpleMailMessage();
    message.setFrom(mailSenderProperties.getFrom());
    message.setTo(to);
    message.setSubject("[Kohere] 비밀번호 재설정 안내");
    message.setText(
        "아래 링크에서 새 비밀번호를 설정해 주세요.\n\n"
            + passwordResetLinks.build(rawToken)
            + "\n\n이 링크는 "
            + ttlMinutes
            + "분 동안만 유효하며 한 번만 사용할 수 있습니다.\n"
            + "비밀번호를 재설정하면 기존에 로그인된 기기에서는 모두 로그아웃됩니다.\n"
            + "본인이 요청하지 않았다면 이 메일을 무시해 주세요. 비밀번호는 변경되지 않습니다.\n\n"
            + "이 메일은 발신 전용이라 회신하실 수 없습니다.");
    try {
      mailSender.send(message);
    } catch (MailException e) {
      throw new EmailDispatchException(e);
    }
  }
}
