package com.kohere.auth.application;

import com.kohere.auth.application.dto.PasswordResetLinkResponse;
import com.kohere.auth.application.dto.PasswordResetTokenVerifyResponse;
import com.kohere.auth.domain.LocalAccount;
import com.kohere.auth.domain.LocalAccountRepository;
import com.kohere.auth.domain.LoginAttemptRateLimiter;
import com.kohere.auth.domain.PasswordHasher;
import com.kohere.auth.domain.PasswordResetLinkEmailSender;
import com.kohere.auth.domain.PasswordResetRateLimiter;
import com.kohere.auth.domain.PasswordResetToken;
import com.kohere.auth.domain.PasswordResetTokenHasher;
import com.kohere.auth.domain.PasswordResetTokenInvalidException;
import com.kohere.auth.domain.PasswordResetTokenRepository;
import com.kohere.auth.domain.RefreshTokenRepository;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 임대인 웹 비밀번호 재설정 유스케이스 — 링크 요청(§1-8) · 토큰 사전 확인(§1-9) · 확정(§1-10), US-1-17. <b>"비밀번호를 잊었다"와 "계정이
 * 잠겼다"의 진입점이 같다</b> — 화면은 둘이지만 API는 하나이고, 확정 한 번이 비밀번호 교체와 잠금 해제를 함께 한다. 잠긴 사람이 아는 비밀번호는 이미 틀린
 * 비밀번호라, 잠금만 풀어 주면 같은 오타로 곧 다시 잠기기 때문이다.
 *
 * <p><b>왜 {@link WebAuthService}에 넣지 않는가 — 트랜잭션 때문이다.</b> 그쪽 {@code login}에는
 * "{@code @Transactional}을 달면 잠금이 영원히 걸리지 않는다"는 제약이 있다(실패 카운터는 예외로 끝나는 그 순간 커밋돼야 하는데 트랜잭션은 정확히 그
 * 반대를 보장한다). 여기 확정 유스케이스는 반대로 <b>트랜잭션이 필요하다</b>. 두 유스케이스를 같은 빈에 두면 언젠가 누군가 클래스 레벨에
 * {@code @Transactional} 한 줄을 붙이고, 그 한 줄이 <b>잠금을 조용히 무력화한다</b> — 잠금 코드는 그대로 있는데 아무도 잠기지 않고, 그 사실은
 * 어떤 테스트도 알려 주지 않는다. 같은 이유로 이 서비스의 확정 메서드는 {@code WebAuthService#login}을 <b>부르지 않는다</b>(트랜잭션 안에서 그
 * 메서드를 호출하는 것 자체가 금지다).
 *
 * <p>이 빈에서 {@code @Transactional}이 안전한 것은 <b>롤백시키면 곤란한 카운터 증가가 없어서</b>다 — 확정 경로의 쓰기는 "비밀번호 교체 + 잠금
 * 해제"라는 되돌아가도 되는 단일 UPDATE 하나뿐이다.
 *
 * <p><b>Redis 쓰기는 트랜잭션 밖이다.</b> 토큰 소비·refresh 무효화·카운터 삭제는 롤백되지 않으므로 순서로만 안전을 보장한다(각 메서드 주석 참조).
 *
 * <p>docs/api/specs/01-auth-onboarding.md §1-8·§1-9·§1-10 · 시퀀스 us-1-17-password-reset.
 */
@Service
@RequiredArgsConstructor
public class WebPasswordResetService {

  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  /**
   * 불투명 토큰 접두 — refresh({@code rt_})와 <b>같은 모양의 다른 접두</b>다. 로그·제보에 섞여 들어온 값이 어느 종류인지 눈으로 구분되고, 무엇보다
   * 두 토큰이 서로의 자리에 제출됐을 때 그 사실이 드러난다(해시 pepper와 키스페이스가 달라 실제로 통하지는 않는다).
   */
  private static final String TOKEN_PREFIX = "pr_";

  /** {@code rt_}와 같은 32바이트 — {@code SecureRandom} 256비트라 추측이 성립하지 않는다는 것이 §1-9가 레이트리밋을 생략한 근거다. */
  private static final int TOKEN_BYTES = 32;

  private final PasswordResetRateLimiter passwordResetRateLimiter;
  private final LocalAccountRepository localAccountRepository;
  private final PasswordResetTokenRepository passwordResetTokenRepository;
  private final PasswordResetTokenHasher passwordResetTokenHasher;
  private final PasswordResetLinkEmailSender passwordResetLinkEmailSender;
  private final PasswordHasher passwordHasher;
  private final RefreshTokenRepository refreshTokenRepository;
  private final LoginAttemptRateLimiter loginAttemptRateLimiter;
  private final AuthProperties authProperties;

  /**
   * 재설정 링크 요청(§1-8). <b>레이트리밋 → 계정 조회 → 토큰 생성 → 메일 발송 → 해시 저장</b> 순이며, 이 순서가 전부 계약이다.
   *
   * <p><b>① 레이트리밋이 계정 조회보다 먼저다.</b> 뒤로 밀면 "가입되지 않은 이메일이라 아무것도 하지 않고 반환"하는 경로가 카운터를 <b>세지 않고</b>
   * 지나가, 존재하지 않는 주소를 무제한으로 두드릴 수 있게 된다.
   *
   * <p><b>② 계정이 없으면 아무것도 하지 않고 정상 반환한다.</b> 미가입 이메일도 <b>같은 200 · 같은 {@code expiresIn}</b>이다.
   * §1-7(이메일 찾기)이 404로 존재를 드러내는 것과 정반대인데, 갈리는 지점은 <b>선행 게이트</b>다 — 저쪽은 SMS 인증 마커 뒤에 있어 호출자가 조회할 수
   * 있는 것이 방금 소유를 증명한 자기 번호뿐이지만, 이 경로는 게이트가 없어 임의의 이메일로 부를 수 있다. 응답을 가르는 순간 완전한 가입 여부 오라클이 된다.
   *
   * <p><b>그 방어는 반쪽이고, 반쪽인 채로 받아들인다.</b> 발송이 <b>동기</b>라 가입된 이메일은 SMTP 왕복 시간만큼 응답이 늦고 발송이 실패하면 502가
   * 나가는 반면, 미가입 이메일은 아무 일도 하지 않고 즉시 200이다 — <b>응답 시간과 status 분포로 존재가 샌다.</b> 위 레이트리밋은 이 누출의 완화책이
   * <b>아니다</b>(한 주소당 한 번만 재 보면 되는 관찰을 시간당 5회 한도가 방해하지 못한다). 균일한 응답을 만드는 정공법은 발송을 큐로 밀어내는 것이지만 이번
   * 범위에 도입하지 않는다 — <b>본문이 같다는 수준까지만 방어하고 타이밍 누출은 알면서 남긴다.</b>
   *
   * <p><b>③ 발송이 성공한 뒤에만 해시를 저장한다(send-then-store).</b> 순서를 뒤집으면 메일을 받지도 못한 사용자의 유효 토큰이 30분 동안 떠 있고,
   * 그 창은 아무도 쓰지 않는 채로 남는 공격 표면일 뿐이다. 인증번호 발송({@code SignupPhoneVerificationService})과 같은 규율이다.
   *
   * <p><b>저장·발송에 쓰는 이메일은 제출값이 아니라 {@code local_accounts}에 저장된 값</b>이다. 웹 자격증명 테이블의 콜레이션은 대소문자·악센트를
   * 구분하지 않아 {@code Kim@x.com}으로 요청해도 {@code kim@x.com} 행이 잡히는데, 제출값을 그대로 쓰면 §1-9의 마스킹 이메일이 달라 보이고
   * §1-10의 카운터 삭제가 <b>실제로 세고 있던 키와 다른 키</b>를 지운다.
   *
   * @param clientIp 호출자 IP(프레젠테이션 계층이 추출해 넘긴다). 알 수 없으면 {@code null}
   */
  public PasswordResetLinkResponse sendResetLink(String email, String clientIp) {
    long ttlSeconds = authProperties.getWeb().getPasswordReset().getTokenTtlSeconds();
    // 조회보다 먼저다 — 뒤로 밀면 미가입 경로가 카운터를 세지 않고 지나간다.
    passwordResetRateLimiter.recordAttempt(email, clientIp);

    Optional<LocalAccount> found = localAccountRepository.findByEmail(email);
    if (found.isEmpty()) {
      // 계정 존재 비노출 — 메일만 보내지 않고 응답은 가입 계정과 완전히 같다.
      return new PasswordResetLinkResponse(ttlSeconds);
    }
    LocalAccount account = found.get();

    String rawToken = generateResetToken();
    // 발송 실패는 502로 전파되고 Redis에는 아무것도 남지 않는다.
    passwordResetLinkEmailSender.send(account.getEmail(), rawToken);
    passwordResetTokenRepository.save(
        PasswordResetToken.issue(
            passwordResetTokenHasher.hash(rawToken),
            account.getUserId(),
            account.getEmail(),
            Instant.now(),
            ttlSeconds));
    return new PasswordResetLinkResponse(ttlSeconds);
  }

  /**
   * 토큰 사전 확인(§1-9). SPA가 재설정 화면에 도착하자마자 부른다 — 이 절이 없으면 사용자는 새 비밀번호를 두 번 입력하고 제출한 뒤에야 "만료된 링크"를 듣는다.
   *
   * <p><b>토큰을 소비하지 않는 것이 계약이다.</b> 그래서 {@link PasswordResetTokenRepository#find}(읽기 전용)를 쓰고 {@link
   * PasswordResetTokenRepository#consume}은 쓰지 않는다. 메일 클라이언트의 링크 프리뷰·기업 메일 게이트웨이의 URL 안전 검사·SPA 개발
   * 모드의 이중 렌더링처럼 <b>사용자가 클릭하기 전에 링크가 열리는 경우가 흔하고</b>, 여기서 토큰을 태우면 정작 본인이 클릭했을 때는 이미 죽은 링크다 — 사용자
   * 눈에는 "메일을 받았는데 언제 눌러도 만료"로 보이고, 서버 로그에도 정상 요청으로만 남아 재현이 어렵다.
   *
   * <p>부재와 만료를 구분하지 않고 같은 422로 낸다 — 구분하면 "존재했지만 이미 쓰였다"까지 알려 주는 오라클이 된다.
   */
  public PasswordResetTokenVerifyResponse verifyToken(String rawToken) {
    Instant now = Instant.now();
    PasswordResetToken token =
        passwordResetTokenRepository
            .find(passwordResetTokenHasher.hash(rawToken))
            .filter(found -> !found.isExpired(now))
            .orElseThrow(PasswordResetTokenInvalidException::new);
    return new PasswordResetTokenVerifyResponse(
        Masks.maskEmail(token.getEmail()), token.remainingSeconds(now));
  }

  /**
   * 재설정 확정(§1-10) — <b>① 토큰 원자 소비(Redis) → ② 비밀번호 교체 + 잠금 해제(MySQL) → ③ refresh 전량 무효화(Redis) → ④
   * 로그인 시도 카운터 삭제(Redis)</b>.
   *
   * <p><b>이 순서가 계약이다.</b> MySQL과 Redis에 걸친 원자성은 존재하지 않으므로(이 메서드의 트랜잭션은 ②만 감싼다), 어디서 끊기든 <b>남는 상태가
   * 안전한 쪽</b>이 되도록 순서를 고정한다. 이 순서에서 중간 실패가 남기는 최악은 "토큰만 소비되고 비밀번호는 그대로"이고, 사용자는 §1-8에서 링크를 다시 받으면
   * 된다 — <b>불편이지 취약점이 아니다.</b> 뒤집어 비밀번호부터 바꾸면 그사이 <b>이미 바뀐 비밀번호의 토큰이 아직 살아 있는 재사용 창</b>이 열린다.
   *
   * <p><b>①은 반드시 원자적이어야 한다</b>({@code GETDEL}). {@code 조회 → 검증 → 삭제}로 쪼개면 같은 링크를 동시에 두 번 눌렀을 때 둘 다
   * 통과해 <b>일회용이 아니게 된다</b> — 더블클릭·메일 프리페치는 흔한 기본 동작이다.
   *
   * <p><b>②의 잠금 해제는 별도 단계가 아니다.</b> {@link LocalAccount#resetPassword}가 비밀번호 해시·{@code
   * failed_login_attempts}·{@code locked_at}을 <b>한 번의 UPDATE로 함께</b> 되돌린다. 나눠 쓰면 비밀번호는 바뀌었는데 잠금이 남는
   * 중간 상태가 생기고, 그 계정은 <b>맞는 비밀번호로도 423</b>이다. 이 한 줄이 잠금 해제 API의 실체이며 별도 해제 엔드포인트를 만들지 않은 이유다.
   *
   * <p><b>③을 빼먹으면 복구가 복구가 아니다.</b> 계정을 이미 빼앗긴 경우 공격자의 refresh 세션이 비밀번호를 바꾼 뒤에도 14일 그대로 살아남는다. 비밀번호
   * 교체는 <b>새 로그인을 막는 일</b>일 뿐 이미 열려 있는 세션을 닫지 않는다.
   *
   * <p><b>④는 이메일 축 하나만 지운다.</b> 잠길 만큼 틀린 사람은 이미 시간당 이메일 한도를 상당히 태워 둔 상태라, 지우지 않으면 재설정을 마치고 돌아간 로그인
   * 화면에서 429를 맞는다(복구를 끝내 놓고 문 앞에서 막는 셈이다). 반면 <b>IP 축은 지우지 않는다</b> — 그 축은 IP를 공유하는 모든 호출자의 것이라, 계정
   * 하나를 가진 공격자가 <b>자기 계정을 재설정하는 것만으로 남의 계정을 향한 시도 예산을 원하는 만큼 되살릴 수 있다</b>. {@link
   * LoginAttemptRateLimiter#clearEmailCounter}가 이메일 축만 지우도록 되어 있는 것이 그 계약이다.
   *
   * <p><b>새 세션을 발급하지 않는다.</b> 응답은 204이고 refresh 쿠키를 싣지 않는다 — ③에서 방금 전량 무효화한 자리에 새 세션을 끼워 넣으면 <b>토큰
   * 하나를 쥔 쪽이 그대로 로그인 상태가 된다</b>(유출된 링크를 주운 사람에게 세션까지 얹어 주는 셈이다). 새 비밀번호를 실제로 아는지는 로그인 화면에서 한 번 더
   * 확인시킨다.
   *
   * <p>토큰이 가리키는 계정이 사라진 경우(탈퇴로 {@code local_accounts} 행이 삭제됨)도 같은 422다 — 404로 가르면 "이 링크는 진짜였고 계정만
   * 사라졌다"까지 알려 주게 된다.
   */
  @Transactional
  public void resetPassword(String rawToken, String newPassword) {
    Instant now = Instant.now();
    // ① 원자 소비. 여기서 실패하면 비밀번호·잠금·세션 어느 것도 건드리지 않는다.
    PasswordResetToken token =
        passwordResetTokenRepository
            .consume(passwordResetTokenHasher.hash(rawToken))
            .filter(consumed -> !consumed.isExpired(now))
            .orElseThrow(PasswordResetTokenInvalidException::new);

    // ② 비밀번호 교체 + 실패 카운터 0 + locked_at 비우기 — 한 UPDATE다.
    LocalAccount account =
        localAccountRepository
            .findByUserId(token.getUserId())
            .orElseThrow(PasswordResetTokenInvalidException::new);
    localAccountRepository.save(account.resetPassword(passwordHasher.hash(newPassword), now));

    // ③ 이미 열려 있는 세션을 닫는다. 비밀번호 교체만으로는 닫히지 않는다.
    refreshTokenRepository.revokeAllByUserId(token.getUserId());

    // ④ 이메일 축만. IP 축을 지우면 자기 계정 재설정으로 남의 계정 시도 예산을 되살릴 수 있다.
    loginAttemptRateLimiter.clearEmailCounter(account.getEmail());
  }

  /**
   * 불투명 재설정 토큰 생성 — {@code "pr_" + Base64Url(SecureRandom 32바이트)}. refresh 토큰과 <b>같은 모양</b>을
   * 쓴다(회전·재사용 탐지 규칙을 두 벌로 만들지 않는 것과 같은 이유로, 토큰 모양도 두 벌로 만들지 않는다).
   *
   * <p>패딩({@code =})을 빼는 것은 URL 안전 때문이다 — 이 값은 쿼리스트링에 실려 메일을 통과하므로, 인코딩이 필요한 문자가 하나라도 있으면 메일 클라이언트나
   * 게이트웨이가 링크를 다시 쓰는 과정에서 값이 바뀔 수 있다.
   *
   * <p><b>반환된 원문이 존재해도 되는 유일한 자리는 메일 본문이다.</b> 호출부는 이 값을 해시해 저장하고 발송기에 넘길 뿐, 로그·응답 어디에도 남기지 않는다.
   */
  private static String generateResetToken() {
    byte[] bytes = new byte[TOKEN_BYTES];
    SECURE_RANDOM.nextBytes(bytes);
    return TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
