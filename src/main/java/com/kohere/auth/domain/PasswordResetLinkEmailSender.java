package com.kohere.auth.domain;

/**
 * 비밀번호 재설정 링크 메일 발송 포트(US-1-17 · 스펙 §1-8). 인증번호 메일({@link VerificationEmailSender})과 <b>별개
 * 포트</b>이며, 채널(SMTP)이 같다는 이유로 합치지 않는다.
 *
 * <p><b>왜 {@code VerificationEmailSender}를 확장하지 않는가 — 두 가지다.</b>
 *
 * <p>(1) 그 포트의 계약은 {@code send(to, code)}, 즉 <b>인증번호 한 개</b>다. 링크 메일이 실어야 하는 것은 6자리 숫자가 아니라 URL이라
 * 파라미터 이름부터 거짓말이 되고, 같은 인터페이스에 오버로드를 얹으면 "이 발송기가 무엇을 보내는지"가 호출부에서 사라진다.
 *
 * <p>(2) <b>더 중요한 이유는 dev에서 조용히 사라지기 때문이다.</b> 인증번호 계층에는 {@code
 * FixedCodeEmailVerificationCodeIssuer}가 끼어 있는데, 이 래퍼는 발송기가 아니라 <b>발급기 레벨에서 메일 발송을 통째로 가로챈다</b>(심사
 * 계정이면 고정 번호를 반환하고 발송을 생략, 심사 계정의 미등록 이메일이면 아예 거절). 재설정 링크를 같은 계층에 얹으면 <b>애플 심사 기간의 dev에서 링크 메일이
 * 발송되지 않고, 응답은 정상 200</b>이다 — 로그에도 실패로 남지 않아 "메일이 안 온다"는 제보가 올 때까지 드러나지 않는다. 계층을 나누면 그 래퍼의 사정권 밖에
 * 있다.
 *
 * <p>발신 주소는 두 발송기가 {@code app.email.from} 하나를 공유한다({@code MailSenderProperties}) — 같은 서비스가 두 주소로
 * 메일을 보내면 사용자는 둘 중 하나를 사칭으로 의심한다.
 */
public interface PasswordResetLinkEmailSender {

  /**
   * 재설정 링크 메일을 <b>동기</b> 발송한다. 성공 반환한 뒤에만 토큰 해시를 저장하는 것이 계약이다(send-then-store, §1-8) — 저장이 먼저면 메일을
   * 받지 못한 사용자의 링크가 30분 동안 살아 있고, 그 창은 아무도 쓰지 않는 유효 토큰이 떠 있는 시간일 뿐이다.
   *
   * <p>링크 조립은 <b>구현이 한다</b>. 응용 계층은 base URL·SPA 경로를 알 필요가 없고, 알게 되면 요청 헤더로 조립하고 싶은 유혹이 그 계층까지 올라온다
   * (호스트 헤더 포이즈닝 — {@code PasswordResetLinks} 참조).
   *
   * @param to 수신 주소. {@code local_accounts}에 저장된 값이다(제출값이 아니다)
   * @param rawToken 토큰 <b>원문</b>. 이 값이 원문으로 존재해도 되는 유일한 자리가 메일 본문이다 — 로그·응답·저장소로 흘려보내지 않는다
   * @throws EmailDispatchException SMTP 장애·타임아웃(전역 핸들러가 502 {@code UPSTREAM_ERROR}로 변환)
   */
  void send(String to, String rawToken);
}
