package com.kohere.auth.domain;

/**
 * 이메일 찾기용 연락처(SMS) 인증번호 <b>발급</b> 포트 — 인증번호 생성과 발송을 한 책임으로 묶는다({@link
 * SignupPhoneVerificationCodeIssuer}와 같은 이유: 둘을 나누면 저장된 해시와 사용자가 받은 코드가 어긋나는 조합이 성립한다).
 *
 * <p><b>앱 심사용 고정 인증번호 우회는 이 경로에 적용되지 않으며 적용해서도 안 된다.</b> local·dev에서 {@link
 * PhoneVerificationCodeIssuer}의 {@code @Primary} 구현은 고정 인증번호를 돌려주는 우회({@code
 * FixedCodePhoneVerificationCodeIssuer})인데, 그 판정 근거는 <b>{@code userId} + 특정 Google 소셜 계정</b>이다. 이메일
 * 찾기는 로그인 ID조차 모르는 비로그인 단계라 {@code userId}가 없고, 번호만으로는 "그 심사 계정인지"를 판정할 근거가 전혀 없다 — 번호로 우회를 열면
 * <b>아무 번호로나 고정 인증번호가 통하는 문</b>이 된다. 그래서 번호만 받는 포트를 따로 두고, 이 경로는 프로파일과 무관하게 <b>항상 실제 발급·발송</b>을 한다.
 *
 * <p>동기 발송이며 발송 실패는 {@link SmsDispatchException}(502)으로 던진다 — 호출자({@code
 * FindEmailPhoneVerificationService})는 이 메서드가 정상 반환한 경우에만 챌린지를 저장한다(send-then-store).
 */
public interface FindEmailPhoneVerificationCodeIssuer {

  /**
   * 인증번호를 발급하고 발송한다.
   *
   * @param phoneNumber 정규화된 인증 대상 휴대폰 번호(발송처)
   * @return 저장할 챌린지의 원본 인증번호
   */
  String issue(String phoneNumber);
}
