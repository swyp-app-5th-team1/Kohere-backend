package com.kohere.auth.infrastructure;

import com.kohere.auth.application.PhoneVerificationProperties;
import com.kohere.auth.domain.FindEmailPhoneVerificationCodeIssuer;
import com.kohere.auth.domain.VerificationSmsSender;
import java.security.SecureRandom;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 이메일 찾기용 연락처 인증번호 발급 구현 — {@link SecureRandom}으로 정책 자릿수({@code app.phone.code-length})만큼 생성하고
 * 가입용·온보딩용과 <b>같은</b> SMS 포트({@link VerificationSmsSender})로 동기 발송한다. 발송 실패({@code
 * SmsDispatchException}, 502)는 그대로 전파되어 호출자가 챌린지를 저장하지 않는다(send-then-store).
 *
 * <p><b>앱 심사용 고정 인증번호 우회({@link FixedCodePhoneVerificationCodeIssuer})는 이 경로에 연결되지 않으며 연결해서도 안
 * 된다.</b> 그 우회는 local·dev에서 {@code PhoneVerificationCodeIssuer}의 {@code @Primary} 구현으로 들어오지만, 켜고 끄는
 * 판정 근거가 <b>{@code userId} + 특정 Google 소셜 계정</b>({@link FixedVerificationPolicy})이다. 이메일 찾기는 로그인
 * ID조차 모르는 비로그인 단계라 {@code userId}가 없고, 번호만으로는 심사 계정인지 가릴 근거가 없다 — 번호로 우회를 열면 <b>아무 번호로나 고정 인증번호가
 * 통하는 문</b>이 되고, 그 문 뒤에는 남의 로그인 이메일이 있다. 그래서 이 구현은 프로파일과 무관하게 항상 실제 발급·발송을 하고, 로컬 수동 테스트는 {@link
 * LoggingVerificationSmsSender}가 인증번호를 콘솔에 찍어 성립시킨다.
 *
 * <p>공유해야 할 것은 실제로 공유한다 — 자릿수 정책은 {@link PhoneVerificationProperties}, 발송은 {@link
 * VerificationSmsSender}(SOLAPI 또는 로깅 폴백)로 다른 두 채널과 같은 빈을 쓴다. 중복은 난수 자릿수 루프뿐이다.
 */
@Component
@RequiredArgsConstructor
class FindEmailPhoneVerificationCodeIssuerImpl implements FindEmailPhoneVerificationCodeIssuer {

  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  private final VerificationSmsSender smsSender;
  private final PhoneVerificationProperties properties;

  @Override
  public String issue(String phoneNumber) {
    String code = generateNumericCode(properties.getCodeLength());
    smsSender.send(phoneNumber, code); // 발송 실패 시 SmsDispatchException(502) — 챌린지 미저장
    return code;
  }

  private static String generateNumericCode(int length) {
    StringBuilder sb = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      sb.append(SECURE_RANDOM.nextInt(10));
    }
    return sb.toString();
  }
}
