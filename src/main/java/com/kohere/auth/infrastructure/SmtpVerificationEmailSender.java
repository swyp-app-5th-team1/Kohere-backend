package com.kohere.auth.infrastructure;

import com.kohere.auth.application.EmailVerificationProperties;
import com.kohere.auth.application.MailSenderProperties;
import com.kohere.auth.domain.EmailDispatchException;
import com.kohere.auth.domain.VerificationEmailSender;
import lombok.RequiredArgsConstructor;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * 인증번호 메일 발송 어댑터(SMTP). {@link JavaMailSender}로 동기 발송하고, provider 장애·타임아웃 등 실패는 {@link
 * EmailDispatchException}(502)으로 변환한다(send-then-store 정책상 발송 성공 시에만 챌린지 확정). 메일 템플릿·다국어는 확인 필요(문서
 * §6).
 */
@Component
@RequiredArgsConstructor
public class SmtpVerificationEmailSender implements VerificationEmailSender {

  private final JavaMailSender mailSender;
  private final EmailVerificationProperties properties;
  private final MailSenderProperties mailSenderProperties;

  @Override
  public void send(String to, String code) {
    SimpleMailMessage message = new SimpleMailMessage();
    message.setFrom(mailSenderProperties.getFrom());
    message.setTo(to);
    message.setSubject("[Kohere] 이메일 인증번호");
    message.setText(
        "인증번호: " + code + "\n유효시간: " + (properties.getCodeTtlSeconds() / 60) + "분 이내에 입력해 주세요.");
    try {
      mailSender.send(message);
    } catch (MailException e) {
      throw new EmailDispatchException(e);
    }
  }
}
