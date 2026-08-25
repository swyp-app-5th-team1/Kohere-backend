package com.kohere.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kohere.TestcontainersConfiguration;
import com.kohere.auth.application.AuthProperties;
import com.kohere.auth.domain.LocalAccount;
import com.kohere.auth.domain.LocalAccountRepository;
import com.kohere.auth.domain.PasswordResetLinkEmailSender;
import com.kohere.auth.domain.VerificationSmsSender;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 임대인 웹 계정 복구 종단 통합 테스트(US-1-16 이메일 찾기 · US-1-17 비밀번호 재설정 = 잠금 해제) — 스펙 §1-5~§1-10을 <b>실제 HTTP
 * 요청</b>으로 완주한다.
 *
 * <p><b>이 테스트가 보는 것은 문서가 아니라 동작이다.</b> 상태 코드만 맞추는 문서 테스트는 "잠긴 계정이 재설정으로 실제로 풀렸는가"를 말해 주지 못한다 — 그
 * 사실은 재설정 뒤에 <b>새 비밀번호로 로그인이 되는지</b>로만 확인된다. 그래서 여기서는 잠금·재설정·로그인·재발급을 한 흐름으로 이어 붙이고, 각 시나리오의 마지막
 * 단정을 언제나 "그래서 사용자가 다시 들어갈 수 있는가"에 둔다.
 *
 * <p><b>{@code @Transactional}을 붙이면 안 된다.</b> 이 클래스가 검증하는 잠금은 {@code WebAuthService#login}이 <b>예외로
 * 끝나는 그 순간</b> 실패 카운터를 커밋해야 성립한다(그 이유는 {@link WebLoginLockIntegrationTest}가 상세히 적어 두었다). 테스트 트랜잭션은
 * 여러 요청을 하나로 묶어 같은 착시를 만들어, <b>잠기지 않는 계정</b>을 상대로 재설정을 통과시키고 초록을 낸다.
 *
 * <p>롤백으로 정리하지 않으므로 연락처·이메일·호출자 IP는 다른 테스트와 겹치지 않는 전용 값을 쓴다 — 컨텍스트 설정이 같은 테스트끼리 같은 컨테이너를 공유하고,
 * {@code users.phone_number}(V23)·{@code local_accounts.email}(V22)에 UNIQUE가 걸려 있다. IP를 시나리오마다 가르는
 * 것은 레이트리밋 카운터를 나누기 위해서다 — 한 시나리오가 태운 예산이 다른 시나리오를 429로 떨어뜨리면 실패 원인이 엉뚱한 곳을 가리킨다.
 *
 * <p><b>모킹은 SMS·메일 발송 포트 둘뿐</b>이다(인증번호·토큰 원문을 캡처해야 그다음 요청을 만들 수 있다). Security·JPA·Redis·JWT·BCrypt는
 * 전부 실제로 구동한다 — 재설정 확정이 MySQL(비밀번호·잠금)과 Redis(토큰·refresh·시도 카운터)에 걸쳐 있어, 어느 한쪽이라도 가짜면 이 기능의 핵심인
 * <b>저장소 간 순서 계약</b>이 검증되지 않는다.
 *
 * <p>스펙: docs/api/specs/01-auth-onboarding.md §1-5~§1-10 · 시퀀스 us-1-16-find-email ·
 * us-1-17-password-reset.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class WebAccountRecoveryIntegrationTest {

  private static final String NAME = "김임대";
  private static final String PASSWORD = "Kohere1!";
  private static final String NEW_PASSWORD = "Kohere2@";
  private static final String OTHER_PASSWORD = "Kohere3#";
  private static final String WRONG_PASSWORD = "Wrong99!";

  /** 재설정 토큰 접두({@code pr_}) — refresh({@code rt_})와 눈으로 구분되는 값이라는 계약을 여기서도 확인한다. */
  private static final String TOKEN_PREFIX = "pr_";

  /** {@code app.auth.web.refresh-cookie.name} — 웹 refresh는 본문이 아니라 이 쿠키로만 오간다(ADR-0048). */
  private static final String REFRESH_COOKIE = "refreshToken";

  @Autowired private WebApplicationContext context;
  @Autowired private AuthProperties authProperties;
  @Autowired private LocalAccountRepository localAccountRepository;

  @MockitoBean private VerificationSmsSender smsSender;
  @MockitoBean private PasswordResetLinkEmailSender passwordResetLinkEmailSender;

  /** 번호 → 방금 발송된 인증번호. 발송기를 가로채지 않으면 §1-6·§1-2를 통과할 방법이 없다. */
  private final Map<String, String> sentCodes = new ConcurrentHashMap<>();

  /** 이메일 → 메일 본문에 실린 토큰 <b>원문</b>. 서버는 해시만 보관하므로 여기가 원문을 볼 수 있는 유일한 자리다(§1-8). */
  private final Map<String, String> sentResetTokens = new ConcurrentHashMap<>();

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    sentCodes.clear();
    sentResetTokens.clear();
    doAnswer(
            inv -> {
              sentCodes.put(inv.getArgument(0), inv.getArgument(1));
              return null;
            })
        .when(smsSender)
        .send(any(), any());
    doAnswer(
            inv -> {
              sentResetTokens.put(inv.getArgument(0), inv.getArgument(1));
              return null;
            })
        .when(passwordResetLinkEmailSender)
        .send(any(), any());
  }

  // ---- §1-8~§1-10 비밀번호 재설정 = 계정 잠금 해제 ----

  /**
   * <b>이 기능의 존재 이유</b> — 잠금은 시간이 지나도 풀리지 않으므로, 재설정 링크 한 번이 실제로 계정을 되돌려 놓지 않으면 임대인은 운영 문의 말고는 들어올 길이
   * 없다. 마지막 단정을 "새 비밀번호로 로그인이 된다"에 두는 것이 핵심이다 — 잠금만 풀리고 비밀번호가 그대로면 같은 오타로 곧 다시 잠긴다.
   */
  @Test
  @DisplayName("상한만큼 틀려 잠긴 계정이 재설정 링크 한 번으로 풀리고 새 비밀번호로 로그인된다")
  void lockedAccountIsRecoveredByPasswordReset() throws Exception {
    String phone = "01055558101";
    String email = "recovery-it-1@work.com";
    String ip = "198.51.100.30";
    signup(phone, email, ip);

    int lockThreshold = authProperties.getWeb().getLoginMaxFailedAttempts();
    for (int attempt = 1; attempt <= lockThreshold; attempt++) {
      login(email, WRONG_PASSWORD, ip).andExpect(status().isUnauthorized());
    }
    // 잠금 판정이 자격증명 대조보다 먼저다 — 비밀번호가 맞아도 423이라 정상 로그인으로는 되돌릴 수 없다.
    login(email, PASSWORD, ip)
        .andExpect(status().isLocked())
        .andExpect(jsonPath("$.error.code").value("AUTH_ACCOUNT_LOCKED"));
    assertThat(account(email).isLocked()).isTrue();

    sendResetLink(email, ip).andExpect(status().isOk());
    String token = sentResetTokens.get(email);
    assertThat(token).as("가입된 이메일에는 링크 메일이 나가야 한다").isNotNull().startsWith(TOKEN_PREFIX);

    resetPassword(token, NEW_PASSWORD).andExpect(status().isNoContent());

    // 한 번의 UPDATE로 세 값이 함께 돌아왔는지 — 하나라도 남으면 맞는 비밀번호로도 423인 계정이 된다.
    LocalAccount recovered = account(email);
    assertThat(recovered.isLocked()).isFalse();
    assertThat(recovered.getLockedAt()).isNull();
    assertThat(recovered.getFailedLoginAttempts()).isZero();

    login(email, NEW_PASSWORD, ip)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.accessToken").isNotEmpty());
    // 옛 비밀번호는 더 이상 통하지 않는다(423이 아니라 401 — 잠금이 아니라 자격증명 문제로 갈렸다).
    login(email, PASSWORD, ip)
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_CREDENTIALS"));
  }

  /**
   * 토큰은 일회용이다(§1-10 ① 원자 소비). 더블클릭·메일 프리페치로 같은 링크가 두 번 도착하는 것은 흔한 기본 동작이라, 두 번째가 통과하면 <b>유출된 링크의
   * 수명이 사실상 TTL 전체</b>가 된다.
   */
  @Test
  @DisplayName("같은 재설정 토큰으로 두 번 확정할 수 없고, 두 번째는 비밀번호를 바꾸지 못한다")
  void resetTokenIsSingleUse() throws Exception {
    String phone = "01055558102";
    String email = "recovery-it-2@work.com";
    String ip = "198.51.100.31";
    signup(phone, email, ip);

    sendResetLink(email, ip).andExpect(status().isOk());
    String token = sentResetTokens.get(email);
    resetPassword(token, NEW_PASSWORD).andExpect(status().isNoContent());

    resetPassword(token, OTHER_PASSWORD)
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.error.code").value("AUTH_PASSWORD_RESET_TOKEN_INVALID"));

    // 거절이 응답만의 일이 아니라는 확인 — 두 번째 비밀번호는 계정에 닿지 않았다.
    login(email, OTHER_PASSWORD, ip).andExpect(status().isUnauthorized());
    login(email, NEW_PASSWORD, ip).andExpect(status().isOk());
  }

  /**
   * 사전 확인(§1-9)은 <b>토큰을 소비하지 않는다</b>. 메일 클라이언트의 링크 미리보기·기업 게이트웨이의 URL 안전 검사가 사용자보다 먼저 링크를 여는 일이 흔해,
   * 여기서 태우면 정작 본인이 클릭했을 때는 이미 죽은 링크다.
   */
  @Test
  @DisplayName("사전 확인은 토큰을 소비하지 않아 여러 번 열어도 확정이 성공한다")
  void tokenPreflightDoesNotConsumeToken() throws Exception {
    String phone = "01055558103";
    String email = "recovery-it-3@work.com";
    String ip = "198.51.100.32";
    signup(phone, email, ip);

    sendResetLink(email, ip).andExpect(status().isOk());
    String token = sentResetTokens.get(email);

    long ttlSeconds = authProperties.getWeb().getPasswordReset().getTokenTtlSeconds();
    verifyResetToken(token)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.email").value("re***@work.com"))
        // expiresIn은 고정값이 아니라 잔여 초다 — 화면 카운트다운이 사실을 말해야 한다.
        .andExpect(jsonPath("$.data.expiresIn").value(lessThanOrEqualTo((int) ttlSeconds)))
        .andExpect(jsonPath("$.data.expiresIn").value(greaterThan(0)));
    // 프리뷰가 두 번 열려도 링크는 살아 있다.
    verifyResetToken(token).andExpect(status().isOk());

    resetPassword(token, NEW_PASSWORD).andExpect(status().isNoContent());
    login(email, NEW_PASSWORD, ip).andExpect(status().isOk());

    // 소비는 확정에서 단 한 번 — 그 뒤에는 사전 확인도 같은 422다(소비 여부를 구분해 알려 주지 않는다).
    verifyResetToken(token)
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.error.code").value("AUTH_PASSWORD_RESET_TOKEN_INVALID"));
  }

  /**
   * §1-8은 <b>계정 존재를 드러내지 않는다</b>. 선행 게이트가 없어 임의의 이메일로 부를 수 있으므로, 응답을 가르는 순간 아무 자격 없이 무한히 두드릴 수 있는
   * 완전한 가입 여부 오라클이 된다. 그래서 status뿐 아니라 <b>본문 전체가 같은지</b>까지 본다(`expiresIn` 한 값이 갈려도 오라클이다).
   */
  @Test
  @DisplayName("미가입 이메일도 가입된 이메일과 완전히 같은 200을 받고, 메일만 나가지 않는다")
  void resetLinkDoesNotRevealAccountExistence() throws Exception {
    String phone = "01055558104";
    String email = "recovery-it-4@work.com";
    String unknownEmail = "recovery-it-unknown@work.com";
    String ip = "198.51.100.33";
    signup(phone, email, ip);

    String registeredBody =
        sendResetLink(email, ip)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String unknownBody =
        sendResetLink(unknownEmail, ip)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(unknownBody).as("본문 한 글자라도 갈리면 가입 여부 오라클이다").isEqualTo(registeredBody);

    verify(passwordResetLinkEmailSender).send(eq(email), anyString());
    verify(passwordResetLinkEmailSender, never()).send(eq(unknownEmail), anyString());
    assertThat(sentResetTokens).doesNotContainKey(unknownEmail);
  }

  /**
   * §1-10 ③ — <b>비밀번호 교체는 새 로그인을 막는 일일 뿐 이미 열려 있는 세션을 닫지 않는다.</b> 계정을 이미 빼앗긴 경우 공격자의 refresh가 재설정
   * 뒤에도 14일 살아남으면 복구가 복구가 아니다.
   *
   * <p>재설정 전에 <b>재발급이 실제로 성공하는 것</b>을 먼저 확인하는 것이 이 테스트의 요령이다 — 그 단계가 없으면 마지막 401은 "쿠키를 잘못 꺼냈다"로도
   * 똑같이 성립해, 무효화가 일어나지 않아도 초록이 난다.
   */
  @Test
  @DisplayName("재설정이 기존 refresh 세션을 전량 무효화하고, 새 세션을 발급하지도 않는다")
  void resetRevokesExistingRefreshSessions() throws Exception {
    String phone = "01055558105";
    String email = "recovery-it-5@work.com";
    String ip = "198.51.100.34";
    String refreshToken = signup(phone, email, ip);

    String rotated =
        refreshCookieOf(
            reissue(refreshToken).andExpect(status().isOk()), "재발급 응답에 회전된 refresh 쿠키가 없다");

    sendResetLink(email, ip).andExpect(status().isOk());
    ResultActions reset =
        resetPassword(sentResetTokens.get(email), NEW_PASSWORD).andExpect(status().isNoContent());
    // 204에는 Set-Cookie가 없다 — 방금 전량 무효화한 자리에 새 세션을 끼워 넣으면 링크를 주운 쪽이 곧 로그인 상태가 된다.
    assertThat(reset.andReturn().getResponse().getCookie(REFRESH_COOKIE)).isNull();

    reissue(rotated)
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_REFRESH_TOKEN"));
  }

  /**
   * §1-10 ④ — <b>복구를 끝내 놓고 문 앞에서 막지 않는다.</b> 잠길 만큼 틀린 사람은 이미 시간당 이메일 한도를 태워 둔 상태라, 카운터를 지우지 않으면 새
   * 비밀번호를 쥐고 돌아간 로그인 화면에서 429를 맞는다.
   *
   * <p><b>한도를 실제로 넘겨 두는 것이 이 테스트의 전부다.</b> 잠금 임계값(10)만큼만 틀리고 마는 흐름은 이메일 한도(그 2배)에 한참 못 미쳐, 카운터를 지우든
   * 말든 로그인이 성공한다 — 즉 <b>무엇도 검증하지 못하는 초록</b>이다. 그래서 잠긴 뒤에도 계속 두드려(잠긴 계정의 423도 시도로 셈해진다) <b>429가 실제로
   * 나는 것을 먼저 확인</b>하고, 그 상태에서 재설정한다. 앞의 429가 없으면 마지막 200은 "한도에 애초에 닿지 않았다"로도 성립해 카운터 삭제를 지워도 초록이
   * 난다.
   */
  @Test
  @DisplayName("재설정이 로그인 시도 카운터를 비워, 한도까지 태운 계정도 곧바로 로그인된다")
  void resetClearsLoginAttemptCounter() throws Exception {
    String phone = "01055558106";
    String email = "recovery-it-6@work.com";
    String ip = "198.51.100.35";
    signup(phone, email, ip);

    int lockThreshold = authProperties.getWeb().getLoginMaxFailedAttempts();
    int emailLimit = authProperties.getWeb().getLogin().getEmailMaxPerHour();
    assertThat(emailLimit)
        .as("이메일 한도가 잠금 임계값보다 작으면 잠금 자체가 도달 불가능해진다(설정 계약)")
        .isGreaterThanOrEqualTo(lockThreshold);

    for (int attempt = 1; attempt <= lockThreshold; attempt++) {
      login(email, WRONG_PASSWORD, ip).andExpect(status().isUnauthorized());
    }
    // 잠긴 뒤의 요청도 시도로 셈해진다 — 여기서 카운터를 이메일 한도에 정확히 붙여 둔다.
    for (int attempt = lockThreshold + 1; attempt <= emailLimit; attempt++) {
      login(email, WRONG_PASSWORD, ip).andExpect(status().isLocked());
    }
    // 한도를 넘겼다 — 이 계정은 이제 잠겨 있을 뿐 아니라 로그인 요청 자체가 받아들여지지 않는다.
    login(email, PASSWORD, ip)
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.error.code").value("TOO_MANY_REQUESTS"));

    sendResetLink(email, ip).andExpect(status().isOk());
    resetPassword(sentResetTokens.get(email), NEW_PASSWORD).andExpect(status().isNoContent());

    // 카운터를 지우지 않았다면 방금 429였던 자리라 이 한 번도 429다.
    login(email, NEW_PASSWORD, ip)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.accessToken").isNotEmpty());
  }

  // ---- §1-5~§1-7 이메일 찾기 ----

  /** SMS로 번호 소유를 증명하고 이름이 맞으면 <b>마스킹된 로그인 ID</b>를 돌려준다. 마커는 성공했을 때만 소비된다(1회용). */
  @Test
  @DisplayName("연락처 인증 뒤 이름이 맞으면 마스킹된 이메일을 돌려주고 마커를 소비한다")
  void findEmailReturnsMaskedEmailAfterPhoneVerification() throws Exception {
    String phone = "01055558107";
    String email = "recovery-it-7@work.com";
    String ip = "198.51.100.36";
    signup(phone, email, ip);

    verifyFindEmailPhone(phone, ip);
    findEmail(phone, NAME)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.email").value("re***@work.com"));

    // 마커 하나로 무제한 반복 조회할 수 없다 — 성공이 마커를 태운다.
    findEmail(phone, NAME)
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.error.code").value("AUTH_PHONE_NOT_VERIFIED"));
  }

  /**
   * <b>이름 불일치와 계정 미존재가 같은 404로 수렴</b>해야 이름 오라클이 닫힌다 — 갈리면 SMS 인증 한 번으로 남의 명의를 한 글자씩 맞혀 볼 수 있다. 반대로
   * 실패한 조회가 마커를 태우면 이름 오타 한 번에 SMS부터 다시 하게 되므로, 마커는 그대로 남아야 한다.
   */
  @Test
  @DisplayName("이름이 다르면 가입 이력 없는 번호와 같은 404이고, 실패는 마커를 태우지 않는다")
  void findEmailConvergesNameMismatchAndMissingAccountOn404() throws Exception {
    String phone = "01055558108";
    String email = "recovery-it-8@work.com";
    String unknownPhone = "01055558109";
    String ip = "198.51.100.37";
    signup(phone, email, ip);

    verifyFindEmailPhone(phone, ip);
    findEmail(phone, "박다른")
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("AUTH_WEB_ACCOUNT_NOT_FOUND"));

    // 가입 이력이 아예 없는 번호 — 위와 코드도 status도 같아야 한다.
    verifyFindEmailPhone(unknownPhone, ip);
    findEmail(unknownPhone, NAME)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("AUTH_WEB_ACCOUNT_NOT_FOUND"));

    // 이름을 고쳐 다시 부르면 통과한다 — 실패한 조회가 마커를 태우지 않았다는 뜻이다.
    findEmail(phone, NAME)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.email").value("re***@work.com"));
  }

  /** 마커 게이트가 §1-7이 404로 계정 존재를 드러낼 수 있는 근거다 — 게이트를 통과하지 못하면 계정 조회 자체를 하지 않는다. */
  @Test
  @DisplayName("연락처 인증 없이 이메일 찾기를 부르면 계정 조회 전에 422로 끊긴다")
  void findEmailRequiresPhoneVerificationMarker() throws Exception {
    String phone = "01055558110";
    String email = "recovery-it-10@work.com";
    String ip = "198.51.100.38";
    signup(phone, email, ip);

    // 가입된 번호·맞는 이름인데도 마커가 없으면 404도 200도 아니다.
    findEmail(phone, NAME)
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.error.code").value("AUTH_PHONE_NOT_VERIFIED"));
  }

  /**
   * <b>키스페이스 분리 검증</b>({@code signup-phone:*} vs {@code find-email:*}). 마커는 "소유 증명"이 아니라 <b>"용도가 붙은
   * 소유 증명"</b>이라, 가입하려고 인증한 사람이 그 번호에 붙어 있는 남의 계정 이메일을 덤으로 조회할 수 있으면 안 된다(중고 번호·가족 명의처럼 번호와 명의자가
   * 어긋나는 경우가 실제로 있다).
   *
   * <p>이 시나리오가 <b>가입된 번호</b>로 도는 것이 중요하다 — 가입 이력이 없는 번호로 돌면 게이트가 뚫려도 404가 나와 통과 여부를 구분하지 못한다. 여기서는
   * 게이트만 열리면 곧바로 200이 나올 상태를 만들어 두고 422를 확인한다.
   */
  @Test
  @DisplayName("가입용 마커로는 이메일 찾기를 통과하지 못한다(마커 키스페이스 분리)")
  void signupMarkerDoesNotOpenFindEmail() throws Exception {
    String phone = "01055558111";
    String email = "recovery-it-11@work.com";
    String ip = "198.51.100.39";
    signup(phone, email, ip);

    // 가입용 마커를 새로 만든다(가입 때 쓴 마커는 소비됐다).
    verifySignupPhone(phone, ip);
    findEmail(phone, NAME)
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.error.code").value("AUTH_PHONE_NOT_VERIFIED"));

    // 막은 것이 게이트지 데이터가 아니라는 확인 — 제대로 된 마커를 받으면 같은 요청이 그대로 통과한다.
    verifyFindEmailPhone(phone, ip);
    findEmail(phone, NAME)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.email").value("re***@work.com"));
  }

  // ---- helpers ----

  private LocalAccount account(String email) {
    return localAccountRepository
        .findByEmail(email)
        .orElseThrow(() -> new AssertionError("웹 자격증명이 없다 — 가입이 커밋되지 않았다: " + email));
  }

  private ResultActions login(String email, String password, String ip) throws Exception {
    return mockMvc.perform(
        post("/api/v1/auth/login")
            .header("X-Forwarded-For", ip)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
  }

  private ResultActions reissue(String refreshToken) throws Exception {
    return mockMvc.perform(
        post("/api/v1/auth/reissue").cookie(new Cookie(REFRESH_COOKIE, refreshToken)));
  }

  private ResultActions sendResetLink(String email, String ip) throws Exception {
    return mockMvc.perform(
        post("/api/v1/auth/password/reset-link")
            .header("X-Forwarded-For", ip)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + email + "\"}"));
  }

  private ResultActions verifyResetToken(String token) throws Exception {
    return mockMvc.perform(
        post("/api/v1/auth/password/reset-token/verify")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"" + token + "\"}"));
  }

  private ResultActions resetPassword(String token, String newPassword) throws Exception {
    return mockMvc.perform(
        post("/api/v1/auth/password/reset")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"" + token + "\",\"newPassword\":\"" + newPassword + "\"}"));
  }

  private ResultActions findEmail(String phoneNumber, String name) throws Exception {
    return mockMvc.perform(
        post("/api/v1/auth/email/find")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"phoneNumber\":\"" + phoneNumber + "\",\"name\":\"" + name + "\"}"));
  }

  /** §1-5·§1-6 완주 — 이메일 찾기용 검증 마커를 남긴다. */
  private void verifyFindEmailPhone(String phoneNumber, String ip) throws Exception {
    mockMvc
        .perform(
            post("/api/v1/auth/phone/find-email/verification-code")
                .header("X-Forwarded-For", ip)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phoneNumber\":\"" + phoneNumber + "\"}"))
        .andExpect(status().isOk())
        // 이 응답은 계정의 유무를 말하지 않는다 — 번호만 마스킹해 돌려준다.
        .andExpect(jsonPath("$.data.phoneNumber").value(maskPhone(phoneNumber)))
        .andExpect(jsonPath("$.data.expiresIn").value(greaterThan(0)));

    mockMvc
        .perform(
            post("/api/v1/auth/phone/find-email/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"phoneNumber\":\""
                        + phoneNumber
                        + "\",\"code\":\""
                        + code(phoneNumber)
                        + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.verified").value(true));
  }

  /** §1-1·§1-2 완주 — 가입용 검증 마커를 남긴다(§1-3의 게이트). */
  private void verifySignupPhone(String phoneNumber, String ip) throws Exception {
    mockMvc
        .perform(
            post("/api/v1/auth/phone/signup/verification-code")
                .header("X-Forwarded-For", ip)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phoneNumber\":\"" + phoneNumber + "\"}"))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            post("/api/v1/auth/phone/signup/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"phoneNumber\":\""
                        + phoneNumber
                        + "\",\"code\":\""
                        + code(phoneNumber)
                        + "\"}"))
        .andExpect(status().isOk());
  }

  /**
   * 가입용 SMS 인증을 거쳐 웹 계정을 만든다 — 복구할 계정이 있어야 시작할 수 있다.
   *
   * <p>가입용 SMS 발송에도 호출자 IP를 싣는다({@code app.auth.signup-phone.ip-max-per-hour}) — 빠뜨리면 이 클래스의 모든 가입이
   * 서블릿 기본 원격 주소 하나로 몰려, 시나리오가 늘어나는 어느 날 관계없는 테스트가 429로 무너진다.
   *
   * @return {@code Set-Cookie}로 내려온 refresh 토큰 원문(웹은 본문에 담지 않는다 — ADR-0048)
   */
  private String signup(String phoneNumber, String email, String ip) throws Exception {
    verifySignupPhone(phoneNumber, ip);
    ResultActions signup =
        mockMvc
            .perform(
                post("/api/v1/auth/signup")
                    .header("X-Forwarded-For", ip)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "name": "%s",
                          "birthDate": "1990-01-01",
                          "phoneNumber": "%s",
                          "email": "%s",
                          "password": "%s",
                          "termsOfServiceAgreed": true,
                          "privacyPolicyAgreed": true,
                          "marketingAgreed": false
                        }
                        """
                            .formatted(NAME, phoneNumber, email, PASSWORD)))
            .andExpect(status().isOk());
    return refreshCookieOf(signup, "가입 응답에 refresh 쿠키가 없다");
  }

  private String refreshCookieOf(ResultActions actions, String message) throws Exception {
    Cookie cookie = actions.andReturn().getResponse().getCookie(REFRESH_COOKIE);
    assertThat(cookie).as(message).isNotNull();
    return cookie.getValue();
  }

  private String code(String phoneNumber) {
    String code = sentCodes.get(phoneNumber);
    assertThat(code).as("SMS 발송기가 인증번호를 넘겨받지 못했다: " + phoneNumber).isNotNull();
    return code;
  }

  /** 응답이 돌려주는 마스킹 규칙({@code 01012345678} → {@code 010-****-5678})을 테스트 쪽에서 독립적으로 계산한다. */
  private static String maskPhone(String phoneNumber) {
    String digits = phoneNumber.replaceAll("\\D", "");
    return digits.substring(0, 3) + "-****-" + digits.substring(digits.length() - 4);
  }
}
