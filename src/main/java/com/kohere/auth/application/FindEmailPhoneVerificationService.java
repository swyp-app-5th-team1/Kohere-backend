package com.kohere.auth.application;

import com.kohere.auth.application.dto.FindEmailPhoneVerificationCodeResponse;
import com.kohere.auth.application.dto.FindEmailPhoneVerifyResponse;
import com.kohere.auth.domain.FindEmailPhoneVerification;
import com.kohere.auth.domain.FindEmailPhoneVerificationCodeIssuer;
import com.kohere.auth.domain.FindEmailPhoneVerificationRepository;
import com.kohere.auth.domain.FindEmailSmsRateLimiter;
import com.kohere.auth.domain.PhoneNotVerifiedException;
import com.kohere.auth.domain.PhoneRateLimitException;
import com.kohere.auth.domain.PhoneVerificationCodeHasher;
import com.kohere.auth.domain.PhoneVerificationFailedException;
import com.kohere.common.request.PhoneNumbers;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 임대인 웹 <b>이메일 찾기</b> 전 연락처(휴대폰) SMS 인증 유스케이스(US-1-16 · 스펙 §1-5·§1-6). 발송(동기·발송 성공 시에만 챌린지 확정)과
 * 확인(해시 대조·시도 상한)을 담당하며, 확인에 성공하면 <b>이메일 조회(§1-7)가 소비할 검증 마커</b>를 남긴다. 그 마커의 게이트 검사({@link
 * #assertVerified})와 성공 후 소비({@link #consumeVerification})도 여기 둔다 — 마커를 남기는 곳과 읽는 곳이 갈리면 번호 정규화 규칙이
 * 두 벌이 된다.
 *
 * <p><b>가입용 {@link SignupPhoneVerificationService}와 절차가 같은데도 재사용하지 않는 이유는 키스페이스다</b>({@code
 * find-email:*} vs {@code signup-phone:*}). 그 서비스를 그대로 부르면 두 흐름이 같은 마커를 읽고 쓰는데, 마커에는 <b>용도 필드가
 * 없어</b>(§1-2가 용도 하나뿐이던 시절에 만들어졌다) 이메일 찾기용으로 받은 인증 하나가 회원가입(§1-3) 게이트까지 통과시킨다 — 인가 범위가 조용히 넓어진다. 반대
 * 방향도 성립한다(가입 화면의 마커가 남의 로그인 이메일을 열어 준다). 값에 필드를 뒤늦게 더하는 대신 키를 나눈 것은, 필드는 읽는 쪽이 검사를 잊을 수 있지만 <b>키가
 * 다르면 조회 자체가 실패</b>하기 때문이다. 레이트리밋 예산({@link FindEmailSmsRateLimiter})을 나눈 것도 같은 성격이다 — 공유하면 사용자가
 * <b>화면을 전환하는 것만으로</b> 서로의 몫을 태워 두세 번 만에 429가 난다.
 *
 * <p><b>정책값은 나누지 않는다</b> — 인증번호 자릿수·코드 TTL·검증 마커 TTL·시도 상한·재발송 간격({@link
 * PhoneVerificationProperties} = {@code app.phone.*})과 해시({@link PhoneVerificationCodeHasher})·SMS
 * 발송 포트는 세 채널이 그대로 공유한다. 같은 정책을 두 벌 두면 한쪽만 바뀌어 발송은 5분, 확인은 3분 같은 조합이 조용히 생긴다. 한도만 {@code
 * app.auth.find-email.*}로 따로 든다.
 *
 * <p><b>확인 실패를 한 코드로 묶는다</b> — 챌린지 부재·불일치·만료·시도 상한 초과가 모두 422 {@link
 * PhoneVerificationFailedException} 이다. 앱 트랙(§4-2)은 시도 초과를 429로 구분하지만, 비로그인 경로에서는 그 구분 자체가 "이 번호에
 * 챌린지가 살아 있다 / 시도가 몇 번 남았다"를 알려주는 신호가 된다.
 *
 * <p><b>이 흐름의 어떤 응답도 계정의 유무를 말하지 않는다</b> — 가입 이력이 없는 번호에도 동일하게 발송하고 동일하게 응답한다. 여기서 걸러 주면 SMS를 받아 볼
 * 필요도 없이 번호 열거가 성립한다. 계정 판정은 이름까지 받아 보는 §1-7에서만 한다.
 *
 * <p><b>번호는 이 경계에서 한 번만 정규화한다</b>({@link PhoneNumbers#normalize}) — 챌린지 키, 검증 마커 키, 레이트리밋 카운터 키가 전부
 * 같은 표준형이라야 한다. 한 곳이라도 원문을 쓰면 {@code 010-1234-5678}로 발송하고 {@code 01012345678}로 확인하는 순간 조용히 실패한다.
 *
 * <p>Redis만 만지고 DB·모듈 간 호출이 없어 트랜잭션 경계를 두지 않는다.
 */
@Service
@RequiredArgsConstructor
public class FindEmailPhoneVerificationService {

  private final FindEmailPhoneVerificationRepository repository;
  private final FindEmailPhoneVerificationCodeIssuer codeIssuer;
  private final PhoneVerificationCodeHasher codeHasher;
  private final FindEmailSmsRateLimiter rateLimiter;
  private final PhoneVerificationProperties properties;

  /**
   * 인증번호 발송. <b>재발송 쿨다운 → 번호·IP 시간당 한도 → 발급·발송 → 챌린지 저장</b> 순으로 진행한다(§1-1과 같은 순서).
   *
   * <p>쿨다운을 한도보다 먼저 보는 것은 의도다 — 60초 안의 재요청(버튼 두 번 누르기)은 어차피 거절되므로 그것으로 시간당 한도까지 깎지 않는다. 둘 다 429라
   * 클라이언트가 보는 응답은 같다.
   *
   * <p>발급 포트가 인증번호 생성과 SMS 발송을 함께 맡고, <b>정상 반환한 뒤에만</b> 해시해 챌린지를 저장한다 — 발송 실패는 502가 전파되고 Redis에는
   * 아무것도 남지 않는다(챌린지를 먼저 쓰면 SMS를 못 받은 사용자가 만료를 기다려야 하고 재발송 쿨다운만 소모한다).
   *
   * @param clientIp 호출자 IP(프레젠테이션 계층이 X-Forwarded-For·remote address에서 추출해 넘긴다)
   */
  public FindEmailPhoneVerificationCodeResponse sendCode(String phoneNumber, String clientIp) {
    String normalized = PhoneNumbers.normalize(phoneNumber);
    Instant now = Instant.now();
    Optional<FindEmailPhoneVerification> existing = repository.findChallenge(normalized);
    if (existing.isPresent()
        && existing
            .get()
            .getIssuedAt()
            .plusSeconds(properties.getResendIntervalSeconds())
            .isAfter(now)) {
      throw new PhoneRateLimitException();
    }
    rateLimiter.recordAttempt(normalized, clientIp);
    String code = codeIssuer.issue(normalized); // 발송 실패 시 502 전파 — 챌린지 미저장
    repository.saveChallenge(
        FindEmailPhoneVerification.issue(
            normalized, codeHasher.hash(code), now, properties.getCodeTtlSeconds()));
    return new FindEmailPhoneVerificationCodeResponse(
        Masks.maskPhone(normalized), properties.getCodeTtlSeconds());
  }

  /**
   * 인증번호 확인. 챌린지 부재(미발송·만료·이미 검증·발송 실패로 미저장)면 올릴 {@code attempts} 레코드가 없어 즉시 422다. 불일치면 {@code
   * attempts}를 올려 저장한 뒤 422이고, 이미 상한에 도달한 챌린지는 해시를 대조하지 않고 422로 끊는다.
   *
   * <p>일치하면 검증 마커(TTL 30분)를 남기고 코드 챌린지를 삭제한다 — 같은 인증번호를 다시 제출해도 챌린지가 없어 실패한다(1회용). 마커는 §1-7이 소비한다.
   */
  public FindEmailPhoneVerifyResponse verify(String phoneNumber, String code) {
    String normalized = PhoneNumbers.normalize(phoneNumber);
    FindEmailPhoneVerification challenge =
        repository.findChallenge(normalized).orElseThrow(PhoneVerificationFailedException::new);
    if (challenge.isExpired(Instant.now())) {
      repository.deleteChallenge(normalized);
      throw new PhoneVerificationFailedException();
    }
    if (challenge.getAttempts() >= properties.getMaxAttempts()) {
      throw new PhoneVerificationFailedException(); // 앱 트랙(§4-2)의 429와 달리 422로 통일한다
    }
    if (!challenge.matches(codeHasher.hash(code))) {
      repository.saveChallenge(challenge.incrementAttempt());
      throw new PhoneVerificationFailedException();
    }
    repository.markVerified(normalized, properties.getVerifiedTtlSeconds());
    repository.deleteChallenge(normalized);
    return new FindEmailPhoneVerifyResponse(Masks.maskPhone(normalized), true);
  }

  /**
   * 이메일 조회(§1-7) 선행 게이트 — 제출된 번호의 <b>이메일 찾기용</b> 검증 마커가 살아 있어야 한다. 없으면(미인증·만료·이미 소비) 422 {@link
   * PhoneNotVerifiedException}이고 계정 조회 자체를 하지 않는다. <b>가입용 마커(§1-2)로는 통과하지 못한다</b> — 다른 키를 본다.
   *
   * <p>이 게이트가 §1-7이 계정 부재를 404로 드러낼 수 있는 근거다 — 호출자가 조회할 수 있는 번호가 <b>방금 소유를 증명한 자기 번호 하나</b>로 닫혀 있어
   * 열거 표면이 없다. 게이트를 느슨하게 하면 404 하나로 임의 번호의 가입 여부를 읽어낼 수 있다.
   *
   * <p>호출자가 이미 정규화한 값을 넘기지만 여기서 한 번 더 접는다({@link PhoneNumbers#normalize}는 멱등) — 마커를 남긴 {@link
   * #verify}와 <b>같은 경계에서 같은 규칙으로</b> 접어야 표기 차이로 조용히 어긋나지 않는다.
   */
  public void assertVerified(String phoneNumber) {
    if (!repository.isVerified(PhoneNumbers.normalize(phoneNumber))) {
      throw new PhoneNotVerifiedException();
    }
  }

  /**
   * 이메일 조회 <b>성공 후</b> 검증 마커 소비(삭제) — 마커 하나로 무제한 반복 조회하는 것을 막는다(1회용).
   *
   * <p><b>실패한 조회는 마커를 태우지 않는다</b>(호출 위치가 계약이다 — {@code FindEmailService}). 이름 오타 한 번에 SMS 인증부터 다시
   * 하게 만들면 정상 사용자만 벌하는 셈이다. 그 대가로 마커 TTL(30분) 안에서 이름을 바꿔 가며 재시도할 여지가 남지만, 애초에 <b>자기 번호로만 열리는
   * 창</b>이고 그 창을 닫는 것은 TTL뿐이라 받아들인 한계다.
   */
  public void consumeVerification(String phoneNumber) {
    repository.deleteVerified(PhoneNumbers.normalize(phoneNumber));
  }
}
