package com.kohere.auth;

import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.document;
import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.resourceDetails;
import static com.kohere.docs.ApiDocsErrors.assertError;
import static com.kohere.docs.ApiDocsErrors.errorSnippet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration;
import static org.springframework.restdocs.payload.PayloadDocumentation.requestFields;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kohere.TestcontainersConfiguration;
import com.kohere.auth.application.AuthProperties;
import com.kohere.auth.domain.EmailDispatchException;
import com.kohere.auth.domain.PasswordResetLinkEmailSender;
import com.kohere.auth.domain.SmsDispatchException;
import com.kohere.auth.domain.VerificationSmsSender;
import com.kohere.docs.ApiDocsTags;
import com.kohere.docs.AuthDocsFields;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.restdocs.RestDocumentationContextProvider;
import org.springframework.restdocs.RestDocumentationExtension;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 임대인 웹 계정 복구(US-1-16·US-1-17) Spring REST Docs 스니펫 생성 테스트 — 이메일 찾기용 SMS 인증(발송·확인) · 가입 이메일 찾기 ·
 * 비밀번호 재설정(링크 발송·토큰 사전 확인·확정)의 성공 응답과 스펙(01-auth-onboarding.md §1-5~§1-10)의 에러 응답을 {@code
 * build/generated-snippets}에 생성한다. <b>이 테스트가 곧 Swagger 노출 경로다</b> — 이 저장소에는 springdoc이 없고 {@code
 * openapi3.yaml}이 REST Docs 스니펫에서만 만들어지므로, 여기 없는 엔드포인트는 <b>문서에 존재하지 않는데 빌드는 초록</b>이다(ADR-0017).
 *
 * <p>외부 발송 두 포트만 모킹한다 — {@link VerificationSmsSender}로 인증번호를, {@link PasswordResetLinkEmailSender}로
 * <b>재설정 토큰 원문</b>을 가로채 다음 단계로 잇는다. 토큰은 Redis에 해시로만 남아 되읽을 수 없으므로 <b>메일 발송이 원문을 볼 수 있는 유일한
 * 자리</b>이고, 그래서 이 모킹이 흉내가 아니라 실제 사용자 경로의 재현이다. Security·JPA·Redis·JWT는 실제 구동한다.
 *
 * <p><b>「비밀번호 찾기」와 「계정 잠금 해제」는 같은 API다</b> — 화면만 둘이고 확정 한 번이 비밀번호 교체와 잠금 해제를 함께 한다. 그래서 잠금 해제 전용
 * 오퍼레이션은 문서에도 없다.
 *
 * <p><b>재설정 컨트롤러는 토글 뒤에 있다</b>({@code app.auth.web.password-reset.enabled}). {@code test} 프로파일이
 * {@code true}라 여기서 경로가 열리며, 토글이 꺼지면 빈이 사라져 <b>경로 자체가 404</b>가 된다 — 문서가 사라지는 것이 아니라 스니펫 생성이 404로
 * 깨지므로 그 회귀는 조용하지 않다.
 *
 * <p><b>테스트 자원을 예약해 쓴다.</b> 연락처는 {@code 01055558001}~, 이메일은 {@code recovery-docs-N@work.com},
 * {@code X-Forwarded-For}는 {@code 203.0.113.90}이다. {@code users.phone_number}(V23)·{@code
 * local_accounts.email}(V22)에 UNIQUE가 걸려 있고 같은 컨테이너·같은 DB·같은 Redis를 다른 테스트와 공유한다 — 특히 <b>발송 경로는 전부
 * IP 축 레이트리밋을 세므로</b> 카운터를 올리는 요청(연락처 인증번호 발송·재설정 링크 발송)에는 빠짐없이 예약 IP 헤더를 실어 버킷을 갈라 둔다. 기본 remote
 * address(127.0.0.1)에 몰면 관계없는 다른 테스트가 429로 깨진다.
 *
 * <p><b>문서 규약(#151)</b> — 오퍼레이션(path+method)당 summary/description 상수를 1벌만 두고 성공·에러 스니펫이 같은 문자열·같은
 * 태그를 쓴다. 케이스 구분은 summary가 아니라 identifier로 한다. 문구·에러코드 배열·필드 기술자는 {@code
 * com.kohere.docs.AuthDocsFields}에 있고, 이 파일은 상수가 많아 정적 임포트 대신 클래스명을 붙여 참조한다(어느 오퍼레이션의 문구인지 호출부에서
 * 보인다).
 */
@SpringBootTest
@ExtendWith(RestDocumentationExtension.class)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class WebAccountRecoveryDocsTest {

  private static final String MALFORMED_BODY = "{ \"oops\" }";

  /** 가입 정책(영문자·숫자·ASCII 특수문자 각 1자 이상, 길이 8~20)을 만족하는 최소 예시. */
  private static final String PASSWORD = "Kohere1!";

  /** 재설정으로 바꿔 넣을 새 비밀번호 — 같은 정책을 그대로 쓴다(복구 경로에만 다른 규칙을 두지 않는다). */
  private static final String NEW_PASSWORD = "Kohere2!";

  /** 정책 위반 예시 — 길이(11자)는 맞지만 숫자·특수문자가 없어 400 INVALID_INPUT(errors[].field=newPassword)이다. */
  private static final String WEAK_PASSWORD = "onlyletters";

  private static final String NAME = "김임대";
  private static final String BIRTH_DATE = "1990-01-01";

  /**
   * 레이트리밋 카운터를 올리는 요청 전용 IP. 이메일 찾기 발송·재설정 링크 발송은 IP 축(각 20회/1시간)을 세므로, 기본 remote
   * address(127.0.0.1)에 몰면 같은 컨테이너를 쓰는 다른 테스트와 예산을 나눠 쓰게 되어 관계없는 단정이 429로 깨진다.
   */
  private static final String RECOVERY_TEST_IP = "203.0.113.90";

  // ── 성공 체인 전용 값 ──
  private static final String DOCS_PHONE = "01055558001";
  private static final String DOCS_EMAIL = "recovery-docs-1@work.com";
  private static final String DOCS_MASKED_PHONE = "010-****-8001";
  private static final String DOCS_MASKED_EMAIL = "re***@work.com";

  // ── 에러 예시 전용 값(성공 예시와 겹치면 UNIQUE 제약·마커 상태가 섞인다) ──
  /** 메일 발송 실패(502) 예시 — 가입된 계정에서만 502가 날 수 있어 실제 웹 계정이 필요하다. */
  private static final String MAIL_FAIL_PHONE = "01055558002";

  private static final String MAIL_FAIL_EMAIL = "recovery-docs-2@work.com";

  /** 번호 인증은 마쳤지만 그 번호로 가입된 웹 계정이 없는 경우 — 이메일 찾기 404 예시. */
  private static final String NO_ACCOUNT_PHONE = "01055558003";

  /** 인증 마커를 한 번도 만들지 않는 번호 — 이메일 찾기 422(미인증) 예시. */
  private static final String UNVERIFIED_PHONE = "01055558004";

  /** 재발송 쿨다운(60초) 미달 429 예시 전용 — 발송을 두 번 연속 때린다. */
  private static final String RESEND_PHONE = "01055558005";

  /** SMS 발송 실패(502) 예시 전용. */
  private static final String SMS_FAIL_PHONE = "01055558006";

  /** 인증번호를 한 번도 발송하지 않은 번호 — 확인 422 예시(챌린지 부재). */
  private static final String NO_CHALLENGE_PHONE = "01055558007";

  /** 가입되지 않은 이메일 — 「미가입도 같은 200」 계약 확인용(스니펫 없음). */
  private static final String UNKNOWN_EMAIL = "recovery-docs-8@work.com";

  /** 발송 한도(이메일 5회/시간)를 실제로 넘기는 전용 이메일. 계정은 만들지 않는다 — 한도는 조회보다 먼저 판정된다. */
  private static final String RATE_LIMITED_EMAIL = "recovery-docs-9@work.com";

  /** 발급된 적이 없는 토큰 — 부재·만료·이미 사용됨을 한 코드로 묶는 422 예시. */
  private static final String UNKNOWN_TOKEN = "pr_neverIssuedTokenForDocsExample0000000000";

  @Autowired private WebApplicationContext context;
  @Autowired private AuthProperties authProperties;
  @MockitoBean private VerificationSmsSender smsSender;
  @MockitoBean private PasswordResetLinkEmailSender resetLinkEmailSender;
  private final Map<String, String> sentCodes = new ConcurrentHashMap<>();
  private final Map<String, String> sentResetTokens = new ConcurrentHashMap<>();
  private MockMvc mockMvc;

  @BeforeEach
  void setUp(RestDocumentationContextProvider restDocumentation) {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .apply(springSecurity())
            .apply(documentationConfiguration(restDocumentation))
            .build();
    // 발송 인증번호를 번호별로 기록(확인 단계에서 사용). 실제 SMS 발송은 하지 않는다.
    // 가입용·이메일 찾기용 발급기가 같은 SMS 포트를 쓰므로 이 목 하나로 양쪽이 잡힌다.
    doAnswer(
            inv -> {
              sentCodes.put(inv.getArgument(0), inv.getArgument(1));
              return null;
            })
        .when(smsSender)
        .send(any(), any());
    // 재설정 토큰 원문을 수신 주소별로 기록. Redis에는 해시만 남아 되읽을 수 없으므로
    // 메일 본문이 원문을 볼 수 있는 유일한 자리이고, 실제 사용자도 여기서 토큰을 얻는다.
    doAnswer(
            inv -> {
              sentResetTokens.put(inv.getArgument(0), inv.getArgument(1));
              return null;
            })
        .when(resetLinkEmailSender)
        .send(any(), any());
  }

  @Test
  @DisplayName("이메일 찾기·비밀번호 재설정 성공 스니펫을 생성하고 204·토큰 1회용 계약을 단정한다")
  void generatesWebAccountRecoverySnippets() throws Exception {
    // 복구 대상 웹 계정 — 이메일 찾기와 재설정이 모두 이 계정을 가리킨다.
    signupWebAccount(DOCS_PHONE, DOCS_EMAIL);

    // §1-5 이메일 찾기용 인증번호 발송(비로그인). 가입용과 같은 정책이지만 챌린지 키스페이스가 다르다.
    mockMvc
        .perform(findEmailCodeRequest(DOCS_PHONE))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.phoneNumber").value(DOCS_MASKED_PHONE))
        .andExpect(jsonPath("$.data.expiresIn").isNumber())
        .andDo(
            document(
                "auth-phone-find-email-verification-code",
                resourceDetails()
                    .tag(ApiDocsTags.AUTH)
                    .summary(AuthDocsFields.FIND_EMAIL_PHONE_CODE_SUMMARY)
                    .description(AuthDocsFields.FIND_EMAIL_PHONE_CODE_DESCRIPTION),
                requestFields(AuthDocsFields.findEmailPhoneCodeRequestFields()),
                responseFields(AuthDocsFields.findEmailPhoneCodeResponseFields())));

    // §1-6 확인 → 이메일 찾기 전용 검증 마커(30분). 확인 응답의 번호도 마스킹된다 —
    // 이 단정이 없으면 평문으로 돌려주도록 바뀌어도 아무 테스트가 깨지지 않는다.
    mockMvc
        .perform(findEmailVerifyRequest(DOCS_PHONE, sentCodes.get(DOCS_PHONE)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.phoneNumber").value(DOCS_MASKED_PHONE))
        .andExpect(jsonPath("$.data.verified").value(true))
        .andDo(
            document(
                "auth-phone-find-email-verify",
                resourceDetails()
                    .tag(ApiDocsTags.AUTH)
                    .summary(AuthDocsFields.FIND_EMAIL_PHONE_VERIFY_SUMMARY)
                    .description(AuthDocsFields.FIND_EMAIL_PHONE_VERIFY_DESCRIPTION),
                requestFields(AuthDocsFields.findEmailPhoneVerifyRequestFields()),
                responseFields(AuthDocsFields.findEmailPhoneVerifyResponseFields())));

    // §1-7 이메일 찾기 — 마커 소비 + 이름 대조. 응답은 local_accounts.email(웹 로그인 ID)의 마스킹 값이다.
    mockMvc
        .perform(findEmailRequest(DOCS_PHONE, NAME))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.email").value(DOCS_MASKED_EMAIL))
        .andDo(
            document(
                "auth-email-find",
                resourceDetails()
                    .tag(ApiDocsTags.AUTH)
                    .summary(AuthDocsFields.FIND_EMAIL_SUMMARY)
                    .description(AuthDocsFields.FIND_EMAIL_DESCRIPTION),
                requestFields(AuthDocsFields.findEmailRequestFields()),
                responseFields(AuthDocsFields.findEmailResponseFields())));

    // 마커는 성공한 조회가 소비한다 — 같은 마커로 두 번 조회하면 422다(무제한 반복 조회 차단).
    assertError(
        mockMvc,
        findEmailRequest(DOCS_PHONE, NAME),
        status().isUnprocessableEntity(),
        "AUTH_PHONE_NOT_VERIFIED");

    // §1-8 재설정 링크 발송. 토큰 원문은 메일로만 나가므로 목이 가로챈 값을 다음 단계로 잇는다.
    mockMvc
        .perform(resetLinkRequest(DOCS_EMAIL))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.expiresIn").isNumber())
        .andDo(
            document(
                "auth-password-reset-link",
                resourceDetails()
                    .tag(ApiDocsTags.AUTH)
                    .summary(AuthDocsFields.PASSWORD_RESET_LINK_SUMMARY)
                    .description(AuthDocsFields.PASSWORD_RESET_LINK_DESCRIPTION),
                requestFields(AuthDocsFields.passwordResetLinkRequestFields()),
                responseFields(AuthDocsFields.passwordResetLinkResponseFields())));

    String rawToken = sentResetTokens.get(DOCS_EMAIL);
    assertThat(rawToken).as("재설정 토큰은 메일 본문으로만 나간다").startsWith("pr_");

    // 미가입 이메일도 같은 200이다(§1-8) — 메일만 보내지 않는다. 응답을 가르면 완전한 가입 여부 오라클이 된다.
    // 스니펫은 만들지 않는다(위 성공과 같은 path·method·status라 예시만 늘어난다) — 계약은 단정으로 못박는다.
    mockMvc
        .perform(resetLinkRequest(UNKNOWN_EMAIL))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.expiresIn").isNumber());
    verify(resetLinkEmailSender, never()).send(eq(UNKNOWN_EMAIL), any());

    // §1-9 토큰 사전 확인 — 토큰을 소비하지 않는 것이 계약이라 아래 확정이 같은 토큰으로 성공해야 한다.
    mockMvc
        .perform(tokenVerifyRequest(rawToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.email").value(DOCS_MASKED_EMAIL))
        .andExpect(jsonPath("$.data.expiresIn").isNumber())
        .andDo(
            document(
                "auth-password-reset-token-verify",
                resourceDetails()
                    .tag(ApiDocsTags.AUTH)
                    .summary(AuthDocsFields.PASSWORD_RESET_TOKEN_VERIFY_SUMMARY)
                    .description(AuthDocsFields.PASSWORD_RESET_TOKEN_VERIFY_DESCRIPTION),
                requestFields(AuthDocsFields.passwordResetTokenVerifyRequestFields()),
                responseFields(AuthDocsFields.passwordResetTokenVerifyResponseFields())));

    // §1-10 확정 — 204이고 본문이 없다. responseFields를 넘기지 않는 것이 계약이다(strict라 빈 본문에서 실패한다).
    // Set-Cookie가 없다는 단정이 「새 세션을 발급하지 않는다」의 유일한 회귀 방어다 — 방금 전량 무효화한 자리에
    // 새 세션을 끼워 넣으면 유출된 링크를 주운 쪽이 그대로 로그인 상태가 된다.
    mockMvc
        .perform(passwordResetRequest(rawToken, NEW_PASSWORD))
        .andExpect(status().isNoContent())
        .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
        .andDo(
            document(
                "auth-password-reset",
                resourceDetails()
                    .tag(ApiDocsTags.AUTH)
                    .summary(AuthDocsFields.PASSWORD_RESET_SUMMARY)
                    .description(AuthDocsFields.PASSWORD_RESET_DESCRIPTION),
                requestFields(AuthDocsFields.passwordResetRequestFields())));

    // 토큰은 확정에서 원자적으로 소비된다 — 같은 링크를 두 번 제출하면 두 번째는 422다(일회용).
    assertError(
        mockMvc,
        passwordResetRequest(rawToken, NEW_PASSWORD),
        status().isUnprocessableEntity(),
        "AUTH_PASSWORD_RESET_TOKEN_INVALID");
  }

  /** 스펙의 "발생 가능한 에러"를 엔드포인트별로 실제 트리거해 스니펫으로 생성하고 status·error.code를 단정한다. */
  @Test
  @DisplayName("이메일 찾기·비밀번호 재설정 에러 스니펫을 생성한다")
  void generatesWebAccountRecoveryErrorSnippets() throws Exception {
    // ===== §1-5 phone/find-email/verification-code =====
    perform(
        findEmailCodeRequest(""),
        status().isBadRequest(),
        "INVALID_INPUT",
        "auth-phone-find-email-verification-code-invalid-input",
        AuthDocsFields.FIND_EMAIL_PHONE_CODE_SUMMARY,
        AuthDocsFields.FIND_EMAIL_PHONE_CODE_DESCRIPTION,
        AuthDocsFields.FIND_EMAIL_PHONE_CODE_400);

    // 문서 스니펫은 본문 없이 만든다(#151-4) — 본문 누락도 같은 MALFORMED_REQUEST라 예시가 중복되지 않는다.
    // 「깨진 JSON 거부」계약은 스니펫 없이 단정만 남겨 회귀를 막는다.
    assertError(
        mockMvc,
        post("/api/v1/auth/phone/find-email/verification-code")
            .contentType(MediaType.APPLICATION_JSON)
            .content(MALFORMED_BODY),
        status().isBadRequest(),
        "MALFORMED_REQUEST");
    perform(
        post("/api/v1/auth/phone/find-email/verification-code")
            .contentType(MediaType.APPLICATION_JSON),
        status().isBadRequest(),
        "MALFORMED_REQUEST",
        "auth-phone-find-email-verification-code-malformed",
        AuthDocsFields.FIND_EMAIL_PHONE_CODE_SUMMARY,
        AuthDocsFields.FIND_EMAIL_PHONE_CODE_DESCRIPTION,
        AuthDocsFields.FIND_EMAIL_PHONE_CODE_400);

    // 재발송 간격 미달 → 429(첫 발송 성공 직후 즉시 재요청). 쿨다운은 시간당 한도보다 먼저 판정되므로
    // 이 두 번째 요청은 번호·IP 카운터를 깎지 않는다.
    mockMvc.perform(findEmailCodeRequest(RESEND_PHONE)).andExpect(status().isOk());
    perform(
        findEmailCodeRequest(RESEND_PHONE),
        status().isTooManyRequests(),
        "TOO_MANY_REQUESTS",
        "auth-phone-find-email-verification-code-rate-limited",
        AuthDocsFields.FIND_EMAIL_PHONE_CODE_SUMMARY,
        AuthDocsFields.FIND_EMAIL_PHONE_CODE_DESCRIPTION,
        AuthDocsFields.FIND_EMAIL_PHONE_CODE_429);

    // SMS 발송 실패(provider 장애·타임아웃) → 502, 챌린지 미저장(send-then-store)
    doThrow(new SmsDispatchException(new RuntimeException("solapi down")))
        .when(smsSender)
        .send(eq(SMS_FAIL_PHONE), any());
    perform(
        findEmailCodeRequest(SMS_FAIL_PHONE),
        status().isBadGateway(),
        "UPSTREAM_ERROR",
        "auth-phone-find-email-verification-code-dispatch-failed",
        AuthDocsFields.FIND_EMAIL_PHONE_CODE_SUMMARY,
        AuthDocsFields.FIND_EMAIL_PHONE_CODE_DESCRIPTION,
        AuthDocsFields.FIND_EMAIL_PHONE_CODE_502);

    // ===== §1-6 phone/find-email/verify =====
    // 발송한 적 없는 번호 → 올릴 시도 레코드가 없어 즉시 422(불일치·만료·시도 초과와 같은 코드)
    perform(
        findEmailVerifyRequest(NO_CHALLENGE_PHONE, "000000"),
        status().isUnprocessableEntity(),
        "AUTH_PHONE_VERIFICATION_FAILED",
        "auth-phone-find-email-verify-failed",
        AuthDocsFields.FIND_EMAIL_PHONE_VERIFY_SUMMARY,
        AuthDocsFields.FIND_EMAIL_PHONE_VERIFY_DESCRIPTION,
        AuthDocsFields.FIND_EMAIL_PHONE_VERIFY_422);

    perform(
        findEmailVerifyRequest("", "123456"),
        status().isBadRequest(),
        "INVALID_INPUT",
        "auth-phone-find-email-verify-invalid-input",
        AuthDocsFields.FIND_EMAIL_PHONE_VERIFY_SUMMARY,
        AuthDocsFields.FIND_EMAIL_PHONE_VERIFY_DESCRIPTION,
        AuthDocsFields.FIND_EMAIL_PHONE_VERIFY_400);

    assertError(
        mockMvc,
        post("/api/v1/auth/phone/find-email/verify")
            .contentType(MediaType.APPLICATION_JSON)
            .content(MALFORMED_BODY),
        status().isBadRequest(),
        "MALFORMED_REQUEST");
    perform(
        post("/api/v1/auth/phone/find-email/verify").contentType(MediaType.APPLICATION_JSON),
        status().isBadRequest(),
        "MALFORMED_REQUEST",
        "auth-phone-find-email-verify-malformed",
        AuthDocsFields.FIND_EMAIL_PHONE_VERIFY_SUMMARY,
        AuthDocsFields.FIND_EMAIL_PHONE_VERIFY_DESCRIPTION,
        AuthDocsFields.FIND_EMAIL_PHONE_VERIFY_400);

    // ===== §1-7 email/find =====
    perform(
        findEmailRequest(UNVERIFIED_PHONE, ""),
        status().isBadRequest(),
        "INVALID_INPUT",
        "auth-email-find-invalid-input",
        AuthDocsFields.FIND_EMAIL_SUMMARY,
        AuthDocsFields.FIND_EMAIL_DESCRIPTION,
        AuthDocsFields.FIND_EMAIL_400);

    assertError(
        mockMvc,
        post("/api/v1/auth/email/find")
            .contentType(MediaType.APPLICATION_JSON)
            .content(MALFORMED_BODY),
        status().isBadRequest(),
        "MALFORMED_REQUEST");
    perform(
        post("/api/v1/auth/email/find").contentType(MediaType.APPLICATION_JSON),
        status().isBadRequest(),
        "MALFORMED_REQUEST",
        "auth-email-find-malformed",
        AuthDocsFields.FIND_EMAIL_SUMMARY,
        AuthDocsFields.FIND_EMAIL_DESCRIPTION,
        AuthDocsFields.FIND_EMAIL_400);

    // 인증 마커 없음 → 422. 게이트의 맨 앞이라 계정 조회에 닿지 않는다(번호 열거 차단).
    perform(
        findEmailRequest(UNVERIFIED_PHONE, NAME),
        status().isUnprocessableEntity(),
        "AUTH_PHONE_NOT_VERIFIED",
        "auth-email-find-phone-not-verified",
        AuthDocsFields.FIND_EMAIL_SUMMARY,
        AuthDocsFields.FIND_EMAIL_DESCRIPTION,
        AuthDocsFields.FIND_EMAIL_422);

    // 번호는 인증했지만 그 번호로 가입된 웹 계정이 없음 → 404. 이름 불일치도 같은 404로 수렴한다(이름 오라클 차단).
    verifyFindEmailPhone(NO_ACCOUNT_PHONE);
    perform(
        findEmailRequest(NO_ACCOUNT_PHONE, NAME),
        status().isNotFound(),
        "AUTH_WEB_ACCOUNT_NOT_FOUND",
        "auth-email-find-not-found",
        AuthDocsFields.FIND_EMAIL_SUMMARY,
        AuthDocsFields.FIND_EMAIL_DESCRIPTION,
        AuthDocsFields.FIND_EMAIL_404);

    // ===== §1-8 password/reset-link =====
    perform(
        resetLinkRequest("not-an-email"),
        status().isBadRequest(),
        "INVALID_INPUT",
        "auth-password-reset-link-invalid-input",
        AuthDocsFields.PASSWORD_RESET_LINK_SUMMARY,
        AuthDocsFields.PASSWORD_RESET_LINK_DESCRIPTION,
        AuthDocsFields.PASSWORD_RESET_LINK_400);

    assertError(
        mockMvc,
        post("/api/v1/auth/password/reset-link")
            .contentType(MediaType.APPLICATION_JSON)
            .content(MALFORMED_BODY),
        status().isBadRequest(),
        "MALFORMED_REQUEST");
    perform(
        post("/api/v1/auth/password/reset-link").contentType(MediaType.APPLICATION_JSON),
        status().isBadRequest(),
        "MALFORMED_REQUEST",
        "auth-password-reset-link-malformed",
        AuthDocsFields.PASSWORD_RESET_LINK_SUMMARY,
        AuthDocsFields.PASSWORD_RESET_LINK_DESCRIPTION,
        AuthDocsFields.PASSWORD_RESET_LINK_400);

    // 메일 발송 실패 → 502. 가입된 계정에서만 날 수 있어 그 자체가 존재 신호이며, 토큰 해시는 저장되지 않는다.
    signupWebAccount(MAIL_FAIL_PHONE, MAIL_FAIL_EMAIL);
    doThrow(new EmailDispatchException(new RuntimeException("smtp down")))
        .when(resetLinkEmailSender)
        .send(eq(MAIL_FAIL_EMAIL), any());
    perform(
        resetLinkRequest(MAIL_FAIL_EMAIL),
        status().isBadGateway(),
        "UPSTREAM_ERROR",
        "auth-password-reset-link-dispatch-failed",
        AuthDocsFields.PASSWORD_RESET_LINK_SUMMARY,
        AuthDocsFields.PASSWORD_RESET_LINK_DESCRIPTION,
        AuthDocsFields.PASSWORD_RESET_LINK_502);

    // 이메일 축 한도 초과 → 429. 계정을 만들지 않은 이메일로 두들겨도 걸린다는 것이 계약이다 —
    // 한도는 계정 조회보다 먼저 판정되므로 존재 여부와 무관하다(미가입 경로가 카운터를 세지 않고 지나가면
    // 존재하지 않는 주소를 무제한으로 두드릴 수 있게 된다).
    for (int i = 0; i < authProperties.getWeb().getPasswordReset().getEmailMaxPerHour(); i++) {
      mockMvc.perform(resetLinkRequest(RATE_LIMITED_EMAIL)).andExpect(status().isOk());
    }
    perform(
        resetLinkRequest(RATE_LIMITED_EMAIL),
        status().isTooManyRequests(),
        "TOO_MANY_REQUESTS",
        "auth-password-reset-link-rate-limited",
        AuthDocsFields.PASSWORD_RESET_LINK_SUMMARY,
        AuthDocsFields.PASSWORD_RESET_LINK_DESCRIPTION,
        AuthDocsFields.PASSWORD_RESET_LINK_429);

    // ===== §1-9 password/reset-token/verify =====
    perform(
        tokenVerifyRequest(""),
        status().isBadRequest(),
        "INVALID_INPUT",
        "auth-password-reset-token-verify-invalid-input",
        AuthDocsFields.PASSWORD_RESET_TOKEN_VERIFY_SUMMARY,
        AuthDocsFields.PASSWORD_RESET_TOKEN_VERIFY_DESCRIPTION,
        AuthDocsFields.PASSWORD_RESET_TOKEN_VERIFY_400);

    assertError(
        mockMvc,
        post("/api/v1/auth/password/reset-token/verify")
            .contentType(MediaType.APPLICATION_JSON)
            .content(MALFORMED_BODY),
        status().isBadRequest(),
        "MALFORMED_REQUEST");
    perform(
        post("/api/v1/auth/password/reset-token/verify").contentType(MediaType.APPLICATION_JSON),
        status().isBadRequest(),
        "MALFORMED_REQUEST",
        "auth-password-reset-token-verify-malformed",
        AuthDocsFields.PASSWORD_RESET_TOKEN_VERIFY_SUMMARY,
        AuthDocsFields.PASSWORD_RESET_TOKEN_VERIFY_DESCRIPTION,
        AuthDocsFields.PASSWORD_RESET_TOKEN_VERIFY_400);

    // 발급된 적 없는 토큰 → 422. 부재·만료·이미 사용됨을 구분하지 않는다.
    perform(
        tokenVerifyRequest(UNKNOWN_TOKEN),
        status().isUnprocessableEntity(),
        "AUTH_PASSWORD_RESET_TOKEN_INVALID",
        "auth-password-reset-token-verify-token-invalid",
        AuthDocsFields.PASSWORD_RESET_TOKEN_VERIFY_SUMMARY,
        AuthDocsFields.PASSWORD_RESET_TOKEN_VERIFY_DESCRIPTION,
        AuthDocsFields.PASSWORD_RESET_TOKEN_VERIFY_422);

    // ===== §1-10 password/reset =====
    // 비밀번호 정책 위반은 Bean Validation 단계에서 걸린다 — 토큰이 유효하지 않아도 400이 먼저 나오고,
    // 그래서 오타 한 번으로 링크가 죽지 않는다(토큰 소비는 이 판정 뒤다).
    mockMvc
        .perform(passwordResetRequest(UNKNOWN_TOKEN, WEAK_PASSWORD))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
        // 어느 필드가 거절됐는지는 errors[]로만 전달된다 — 비밀번호 정책은 newPassword 필드로 특정돼야
        // 프런트가 그 입력란에 사유를 띄울 수 있다.
        .andExpect(jsonPath("$.error.errors[0].field").value("newPassword"))
        .andDo(
            errorSnippet(
                "auth-password-reset-invalid-input",
                ApiDocsTags.AUTH,
                AuthDocsFields.PASSWORD_RESET_SUMMARY,
                AuthDocsFields.PASSWORD_RESET_DESCRIPTION,
                AuthDocsFields.PASSWORD_RESET_400));

    assertError(
        mockMvc,
        post("/api/v1/auth/password/reset")
            .contentType(MediaType.APPLICATION_JSON)
            .content(MALFORMED_BODY),
        status().isBadRequest(),
        "MALFORMED_REQUEST");
    perform(
        post("/api/v1/auth/password/reset").contentType(MediaType.APPLICATION_JSON),
        status().isBadRequest(),
        "MALFORMED_REQUEST",
        "auth-password-reset-malformed",
        AuthDocsFields.PASSWORD_RESET_SUMMARY,
        AuthDocsFields.PASSWORD_RESET_DESCRIPTION,
        AuthDocsFields.PASSWORD_RESET_400);

    perform(
        passwordResetRequest(UNKNOWN_TOKEN, NEW_PASSWORD),
        status().isUnprocessableEntity(),
        "AUTH_PASSWORD_RESET_TOKEN_INVALID",
        "auth-password-reset-token-invalid",
        AuthDocsFields.PASSWORD_RESET_SUMMARY,
        AuthDocsFields.PASSWORD_RESET_DESCRIPTION,
        AuthDocsFields.PASSWORD_RESET_422);
  }

  // ---- helpers ----

  /**
   * 에러 스니펫 1건. summary·description·tag는 <b>성공 스니펫과 같은 상수</b>를 받아야 한다 — 생성기가 같은 {@code (path,
   * method)} 모델의 문구 중 첫 non-blank 하나만 채택하고 그 순서가 파일 순회에 좌우되기 때문이다. {@code errorCodes}는 오퍼레이션+status
   * 단위 상수다.
   */
  private void perform(
      MockHttpServletRequestBuilder request,
      ResultMatcher expectedStatus,
      String expectedCode,
      String identifier,
      String summary,
      String description,
      String... errorCodes)
      throws Exception {
    mockMvc
        .perform(request)
        .andExpect(expectedStatus)
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.error.code").value(expectedCode))
        .andDo(errorSnippet(identifier, ApiDocsTags.AUTH, summary, description, errorCodes));
  }

  /** 이메일 찾기용 SMS 인증 완주(발송 → 확인) — 이메일 조회가 소비할 검증 마커를 남긴다. */
  private void verifyFindEmailPhone(String phoneNumber) throws Exception {
    mockMvc.perform(findEmailCodeRequest(phoneNumber)).andExpect(status().isOk());
    mockMvc
        .perform(findEmailVerifyRequest(phoneNumber, sentCodes.get(phoneNumber)))
        .andExpect(status().isOk());
  }

  /** 복구 대상 웹 계정을 만든다(가입용 SMS 인증 → 회원가입) — 복구 경로는 계정이 있어야 시작할 수 있다. */
  private void signupWebAccount(String phoneNumber, String email) throws Exception {
    mockMvc
        .perform(
            post("/api/v1/auth/phone/signup/verification-code")
                .header("X-Forwarded-For", RECOVERY_TEST_IP)
                .contentType(MediaType.APPLICATION_JSON)
                .content(phoneJson(phoneNumber)))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            post("/api/v1/auth/phone/signup/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(verifyJson(phoneNumber, sentCodes.get(phoneNumber))))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(signupJson(phoneNumber, email)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("ACTIVE"));
  }

  /** 발송 경로에는 예약 IP를 실어 다른 테스트와 IP 축 예산을 나눠 쓰지 않게 한다. */
  private MockHttpServletRequestBuilder findEmailCodeRequest(String phoneNumber) {
    return post("/api/v1/auth/phone/find-email/verification-code")
        .header("X-Forwarded-For", RECOVERY_TEST_IP)
        .contentType(MediaType.APPLICATION_JSON)
        .content(phoneJson(phoneNumber));
  }

  private MockHttpServletRequestBuilder findEmailVerifyRequest(String phoneNumber, String code) {
    return post("/api/v1/auth/phone/find-email/verify")
        .contentType(MediaType.APPLICATION_JSON)
        .content(verifyJson(phoneNumber, code));
  }

  private MockHttpServletRequestBuilder findEmailRequest(String phoneNumber, String name) {
    return post("/api/v1/auth/email/find")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"phoneNumber\":\"" + phoneNumber + "\",\"name\":\"" + name + "\"}");
  }

  /** 링크 발송도 IP 축(20회/1시간)을 세므로 예약 IP를 붙인다. */
  private MockHttpServletRequestBuilder resetLinkRequest(String email) {
    return post("/api/v1/auth/password/reset-link")
        .header("X-Forwarded-For", RECOVERY_TEST_IP)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"email\":\"" + email + "\"}");
  }

  private MockHttpServletRequestBuilder tokenVerifyRequest(String token) {
    return post("/api/v1/auth/password/reset-token/verify")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"token\":\"" + token + "\"}");
  }

  private MockHttpServletRequestBuilder passwordResetRequest(String token, String newPassword) {
    return post("/api/v1/auth/password/reset")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"token\":\"" + token + "\",\"newPassword\":\"" + newPassword + "\"}");
  }

  private static String phoneJson(String phoneNumber) {
    return "{\"phoneNumber\":\"" + phoneNumber + "\"}";
  }

  private static String verifyJson(String phoneNumber, String code) {
    return "{\"phoneNumber\":\"" + phoneNumber + "\",\"code\":\"" + code + "\"}";
  }

  private static String signupJson(String phoneNumber, String email) {
    return """
        {
          "name": "%s",
          "birthDate": "%s",
          "phoneNumber": "%s",
          "email": "%s",
          "password": "%s",
          "termsOfServiceAgreed": true,
          "privacyPolicyAgreed": true,
          "marketingAgreed": false
        }
        """
        .formatted(NAME, BIRTH_DATE, phoneNumber, email, PASSWORD);
  }
}
