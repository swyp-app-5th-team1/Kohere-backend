package com.kohere.auth;

import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.document;
import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.resourceDetails;
import static com.kohere.docs.ApiDocsErrors.assertError;
import static com.kohere.docs.ApiDocsErrors.errorSnippet;
import static com.kohere.docs.AuthDocsFields.SIGNUP_PHONE_CODE_400;
import static com.kohere.docs.AuthDocsFields.SIGNUP_PHONE_CODE_429;
import static com.kohere.docs.AuthDocsFields.SIGNUP_PHONE_CODE_502;
import static com.kohere.docs.AuthDocsFields.SIGNUP_PHONE_CODE_DESCRIPTION;
import static com.kohere.docs.AuthDocsFields.SIGNUP_PHONE_CODE_SUMMARY;
import static com.kohere.docs.AuthDocsFields.SIGNUP_PHONE_VERIFY_400;
import static com.kohere.docs.AuthDocsFields.SIGNUP_PHONE_VERIFY_422;
import static com.kohere.docs.AuthDocsFields.SIGNUP_PHONE_VERIFY_DESCRIPTION;
import static com.kohere.docs.AuthDocsFields.SIGNUP_PHONE_VERIFY_SUMMARY;
import static com.kohere.docs.AuthDocsFields.WEB_LOGIN_400;
import static com.kohere.docs.AuthDocsFields.WEB_LOGIN_423;
import static com.kohere.docs.AuthDocsFields.WEB_LOGIN_429;
import static com.kohere.docs.AuthDocsFields.WEB_LOGIN_DESCRIPTION;
import static com.kohere.docs.AuthDocsFields.WEB_LOGIN_SUMMARY;
import static com.kohere.docs.AuthDocsFields.WEB_SIGNUP_400;
import static com.kohere.docs.AuthDocsFields.WEB_SIGNUP_409;
import static com.kohere.docs.AuthDocsFields.WEB_SIGNUP_422;
import static com.kohere.docs.AuthDocsFields.WEB_SIGNUP_DESCRIPTION;
import static com.kohere.docs.AuthDocsFields.WEB_SIGNUP_SUMMARY;
import static com.kohere.docs.AuthDocsFields.signupPhoneCodeRequestFields;
import static com.kohere.docs.AuthDocsFields.signupPhoneCodeResponseFields;
import static com.kohere.docs.AuthDocsFields.signupPhoneVerifyRequestFields;
import static com.kohere.docs.AuthDocsFields.signupPhoneVerifyResponseFields;
import static com.kohere.docs.AuthDocsFields.webLoginRequestFields;
import static com.kohere.docs.AuthDocsFields.webLoginResponseFields;
import static com.kohere.docs.AuthDocsFields.webSignupRequestFields;
import static com.kohere.docs.AuthDocsFields.webSignupResponseFields;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 임대인 웹 트랙(ADR-0047·ADR-0048) Spring REST Docs 스니펫 생성 테스트 — 가입용 연락처 SMS 인증(발송·확인)·웹 회원가입·웹 로그인의 성공
 * 응답과 스펙(01-auth-onboarding.md §1-1~§1-4)의 에러 응답을 {@code build/generated-snippets}에 생성한다. <b>이 테스트가
 * 곧 Swagger 노출 경로다</b> — 이 저장소에는 springdoc이 없고 {@code openapi3.yaml}이 REST Docs 스니펫에서만 만들어지므로, 여기
 * 없는 엔드포인트는 문서에 존재하지 않는다(ADR-0017).
 *
 * <p>SMS 발송({@link VerificationSmsSender})만 모킹해 인증번호를 캡처하고 Security·JPA·Redis·JWT는 실제 구동한다. 소셜 OIDC
 * 가짜 주입이 없는 것은 <b>웹 트랙이 소셜을 전혀 타지 않기 때문</b>이다 — 가입·로그인 모두 permitAll이고 계정 생성은 폼 하나로 끝난다. 앱 계정과의
 * 연동({@code linked=true})·병합은 소셜 로그인이 선행되는 별도 시나리오라 양방향 연동 통합 테스트가 맡는다.
 *
 * <p><b>refresh 쿠키 계약을 여기서 못박는다</b>(ADR-0048) — 가입·로그인 응답의 {@code Set-Cookie}가 {@code HttpOnly} ·
 * {@code Secure} · {@code SameSite=Lax} · {@code Path=/api/v1/auth}인지와, <b>응답 본문에 {@code
 * refreshToken}이 없는지</b>를 단정한다. 본문에도 실리면 {@code HttpOnly}로 막으려던 XSS 유출 경로가 그 자리에서 다시 열리는데, 그 회귀는 문서
 * 스니펫만으로는 드러나지 않는다.
 *
 * <p><b>연락처·이메일은 이 파일 안에서 전역 유일해야 한다</b> — {@code users.phone_number}(V23)와 {@code
 * local_accounts.email}(V22)에 UNIQUE가 걸려 있고 두 테스트 메서드가 같은 컨테이너·같은 DB를 쓴다. 레이트리밋 카운터(Redis)도 공유하므로
 * 시도를 많이 쌓는 케이스만 {@code X-Forwarded-For}로 IP 버킷을 갈라 둔다.
 *
 * <p><b>문서 규약(#151)</b> — 오퍼레이션(path+method)당 summary/description 상수를 1벌만 두고 성공·에러 스니펫이 같은 문자열·같은
 * 태그를 쓴다. 케이스 구분은 summary가 아니라 identifier로 한다. 문구·에러코드 배열·필드 기술자는 {@code
 * com.kohere.docs.AuthDocsFields}에 있다.
 */
@SpringBootTest
@ExtendWith(RestDocumentationExtension.class)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class WebLandlordAuthDocsTest {

  private static final String MALFORMED_BODY = "{ \"oops\" }";

  /** 정책(D1)을 만족하는 최소 예시 — 영문자·숫자·ASCII 특수문자 각 1자 이상, 길이 8. */
  private static final String PASSWORD = "Kohere1!";

  private static final String WRONG_PASSWORD = "Wrong99!";

  /** 정책 위반 예시 — 길이(11자)는 맞지만 숫자·특수문자가 없어 400 INVALID_INPUT(errors[].field=password)이다. */
  private static final String WEAK_PASSWORD = "onlyletters";

  private static final String NAME = "김임대";
  private static final String BIRTH_DATE = "1990-01-01";

  // ── 성공 예시 전용 값 ──
  private static final String DOCS_PHONE = "01055550001";
  private static final String DOCS_EMAIL = "docs-web-landlord@work.example";

  // ── 에러 예시 전용 값(성공 예시와 겹치면 UNIQUE 제약에 걸린다) ──
  /** 에러 예시의 기준 계정 — 이 계정이 이메일 중복(409)과 웹 계정 중복(409) 두 케이스의 상대편이 된다. */
  private static final String EXISTING_PHONE = "01055550002";

  private static final String EXISTING_EMAIL = "err-web-existing@work.example";
  private static final String AGREEMENT_PHONE = "01055550003";
  private static final String DUPLICATE_EMAIL_PHONE = "01055550004";
  private static final String RESEND_PHONE = "01055550005";
  private static final String DISPATCH_FAIL_PHONE = "01055550006";

  /** 인증번호를 한 번도 발송하지 않는 번호 — 확인 422와 가입 422(미인증) 예시에 쓴다. */
  private static final String UNVERIFIED_PHONE = "01055550007";

  /** 비밀번호 불일치 401의 error.details 예시 전용 계정 — 잠금 예시와 카운터가 섞이면 안 된다. */
  private static final String WRONG_PASSWORD_PHONE = "01055550010";

  private static final String WRONG_PASSWORD_EMAIL = "err-web-wrongpw@work.example";

  private static final String LOCKED_PHONE = "01055550008";
  private static final String LOCKED_EMAIL = "err-web-locked@work.example";

  /** 시도 한도(이메일 한도/시간)를 실제로 넘기는 전용 이메일. 계정은 만들지 않는다 — 한도는 조회보다 먼저 판정된다. */
  private static final String RATE_LIMITED_EMAIL = "err-web-ratelimit@work.example";

  /**
   * 시도를 대량으로 쌓는 케이스 전용 IP. 기본 remote address(127.0.0.1)에 몰면 같은 컨테이너를 쓰는 다른 케이스가 IP 한도를 나눠 쓰게 되어,
   * 관계없는 단정이 429로 깨진다.
   */
  private static final String RATE_LIMIT_TEST_IP = "203.0.113.77";

  @Autowired private WebApplicationContext context;
  @Autowired private AuthProperties authProperties;
  @MockitoBean private VerificationSmsSender smsSender;
  private final Map<String, String> sentCodes = new ConcurrentHashMap<>();
  private MockMvc mockMvc;

  @BeforeEach
  void setUp(RestDocumentationContextProvider restDocumentation) {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .apply(springSecurity())
            .apply(documentationConfiguration(restDocumentation))
            .build();
    // 발송 인증번호를 번호별로 기록(확인 단계에서 사용). 실제 SMS 발송은 하지 않는다.
    // 가입용 발급기는 온보딩용과 같은 SMS 포트를 쓰므로 이 목 하나로 양쪽이 잡힌다.
    doAnswer(
            inv -> {
              sentCodes.put(inv.getArgument(0), inv.getArgument(1));
              return null;
            })
        .when(smsSender)
        .send(any(), any());
  }

  @Test
  @DisplayName("웹 가입용 인증·회원가입·로그인 성공 스니펫을 생성하고 refresh 쿠키 계약을 단정한다")
  void generatesWebLandlordAuthSnippets() throws Exception {
    // 가입용 인증번호 발송(비로그인) — 온보딩용과 달리 Authorization 헤더가 없다.
    mockMvc
        .perform(
            post("/api/v1/auth/phone/signup/verification-code")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phoneNumber\":\"" + DOCS_PHONE + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.phoneNumber").value("010-****-0001"))
        .andDo(
            document(
                "auth-phone-signup-verification-code",
                resourceDetails()
                    .tag(ApiDocsTags.AUTH)
                    .summary(SIGNUP_PHONE_CODE_SUMMARY)
                    .description(SIGNUP_PHONE_CODE_DESCRIPTION),
                requestFields(signupPhoneCodeRequestFields()),
                responseFields(signupPhoneCodeResponseFields())));

    // 가입용 인증번호 확인 → 번호 키 검증 마커(30분)
    mockMvc
        .perform(
            post("/api/v1/auth/phone/signup/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(verifyJson(DOCS_PHONE, sentCodes.get(DOCS_PHONE))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.verified").value(true))
        // 확인 응답의 번호도 마스킹된다 — 스펙과 Swagger가 그렇게 약속하는데 이 단언이 없으면
        // 평문으로 돌려주도록 바뀌어도 아무 테스트가 깨지지 않는다(발송 응답만 검사하고 있었다).
        .andExpect(jsonPath("$.data.phoneNumber").value("010-****-0001"))
        .andDo(
            document(
                "auth-phone-signup-verify",
                resourceDetails()
                    .tag(ApiDocsTags.AUTH)
                    .summary(SIGNUP_PHONE_VERIFY_SUMMARY)
                    .description(SIGNUP_PHONE_VERIFY_DESCRIPTION),
                requestFields(signupPhoneVerifyRequestFields()),
                responseFields(signupPhoneVerifyResponseFields())));

    // 웹 회원가입 — 같은 번호의 앱 계정이 없으므로 새 계정을 만들어 한 트랜잭션으로 ACTIVE까지 완주한다(linked=false).
    // refresh는 본문이 아니라 Set-Cookie로만 내려간다(ADR-0048) — 아래 단정이 그 계약의 유일한 회귀 방어다.
    mockMvc
        .perform(
            post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(signupJson(DOCS_PHONE, DOCS_EMAIL, PASSWORD)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.linked").value(false))
        .andExpect(jsonPath("$.data.onboardingRequired").value(false))
        .andExpect(jsonPath("$.data.status").value("ACTIVE"))
        .andExpect(jsonPath("$.data.email").value(DOCS_EMAIL))
        .andExpect(jsonPath("$.data.name").value(NAME))
        .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
        .andExpectAll(refreshCookieContract())
        .andDo(
            document(
                "auth-signup",
                resourceDetails()
                    .tag(ApiDocsTags.AUTH)
                    .summary(WEB_SIGNUP_SUMMARY)
                    .description(WEB_SIGNUP_DESCRIPTION),
                requestFields(webSignupRequestFields()),
                responseFields(webSignupResponseFields())));

    // 웹 로그인 — 가입과 같은 쿠키 속성으로 refresh를 내리고 본문에는 싣지 않는다.
    mockMvc
        .perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginJson(DOCS_EMAIL, PASSWORD)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.onboardingRequired").value(false))
        .andExpect(jsonPath("$.data.status").value("ACTIVE"))
        .andExpect(jsonPath("$.data.email").value(DOCS_EMAIL))
        .andExpect(jsonPath("$.data.name").value(NAME))
        .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
        .andExpectAll(refreshCookieContract())
        .andDo(
            document(
                "auth-login",
                resourceDetails()
                    .tag(ApiDocsTags.AUTH)
                    .summary(WEB_LOGIN_SUMMARY)
                    .description(WEB_LOGIN_DESCRIPTION),
                requestFields(webLoginRequestFields()),
                responseFields(webLoginResponseFields())));
  }

  /** 스펙의 "발생 가능한 에러"를 엔드포인트별로 실제 트리거해 스니펫으로 생성하고 status·error.code를 단정한다. */
  @Test
  @DisplayName("웹 가입용 인증·회원가입·로그인 에러 스니펫을 생성한다")
  void generatesWebLandlordAuthErrorSnippets() throws Exception {
    // 에러 예시의 상대편 계정 — 이메일 중복(409)과 웹 계정 중복(409)이 둘 다 이 계정을 가리킨다.
    verifyPhone(EXISTING_PHONE);
    signup(EXISTING_PHONE, EXISTING_EMAIL, PASSWORD).andExpect(status().isOk());

    // ===== phone/signup/verification-code =====
    perform(
        post("/api/v1/auth/phone/signup/verification-code")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"phoneNumber\":\"\"}"),
        status().isBadRequest(),
        "INVALID_INPUT",
        "auth-phone-signup-verification-code-invalid-input",
        SIGNUP_PHONE_CODE_SUMMARY,
        SIGNUP_PHONE_CODE_DESCRIPTION,
        SIGNUP_PHONE_CODE_400);

    // 문서 스니펫은 본문 없이 만든다(#151-4) — 본문 누락도 같은 MALFORMED_REQUEST라 예시가 중복되지 않는다.
    // 「깨진 JSON 거부」계약은 스니펫 없이 단정만 남겨 회귀를 막는다.
    assertError(
        mockMvc,
        post("/api/v1/auth/phone/signup/verification-code")
            .contentType(MediaType.APPLICATION_JSON)
            .content(MALFORMED_BODY),
        status().isBadRequest(),
        "MALFORMED_REQUEST");
    perform(
        post("/api/v1/auth/phone/signup/verification-code").contentType(MediaType.APPLICATION_JSON),
        status().isBadRequest(),
        "MALFORMED_REQUEST",
        "auth-phone-signup-verification-code-malformed",
        SIGNUP_PHONE_CODE_SUMMARY,
        SIGNUP_PHONE_CODE_DESCRIPTION,
        SIGNUP_PHONE_CODE_400);

    // 재발송 간격 미달 → 429 (첫 발송 성공 직후 즉시 재요청). 쿨다운은 시간당 한도보다 먼저 판정되므로
    // 이 두 번째 요청은 번호·IP 카운터를 깎지 않는다.
    mockMvc
        .perform(
            post("/api/v1/auth/phone/signup/verification-code")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phoneNumber\":\"" + RESEND_PHONE + "\"}"))
        .andExpect(status().isOk());
    perform(
        post("/api/v1/auth/phone/signup/verification-code")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"phoneNumber\":\"" + RESEND_PHONE + "\"}"),
        status().isTooManyRequests(),
        "TOO_MANY_REQUESTS",
        "auth-phone-signup-verification-code-rate-limited",
        SIGNUP_PHONE_CODE_SUMMARY,
        SIGNUP_PHONE_CODE_DESCRIPTION,
        SIGNUP_PHONE_CODE_429);

    // SMS 발송 실패(provider 장애·타임아웃) → 502, 챌린지 미저장(send-then-store)
    doThrow(new SmsDispatchException(new RuntimeException("solapi down")))
        .when(smsSender)
        .send(eq(DISPATCH_FAIL_PHONE), any());
    perform(
        post("/api/v1/auth/phone/signup/verification-code")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"phoneNumber\":\"" + DISPATCH_FAIL_PHONE + "\"}"),
        status().isBadGateway(),
        "UPSTREAM_ERROR",
        "auth-phone-signup-verification-code-dispatch-failed",
        SIGNUP_PHONE_CODE_SUMMARY,
        SIGNUP_PHONE_CODE_DESCRIPTION,
        SIGNUP_PHONE_CODE_502);

    // ===== phone/signup/verify =====
    // 발송한 적 없는 번호 → 올릴 시도 레코드가 없어 즉시 422(불일치·만료·시도 초과와 같은 코드)
    perform(
        post("/api/v1/auth/phone/signup/verify")
            .contentType(MediaType.APPLICATION_JSON)
            .content(verifyJson(UNVERIFIED_PHONE, "000000")),
        status().isUnprocessableEntity(),
        "AUTH_PHONE_VERIFICATION_FAILED",
        "auth-phone-signup-verify-failed",
        SIGNUP_PHONE_VERIFY_SUMMARY,
        SIGNUP_PHONE_VERIFY_DESCRIPTION,
        SIGNUP_PHONE_VERIFY_422);

    perform(
        post("/api/v1/auth/phone/signup/verify")
            .contentType(MediaType.APPLICATION_JSON)
            .content(verifyJson("", "123456")),
        status().isBadRequest(),
        "INVALID_INPUT",
        "auth-phone-signup-verify-invalid-input",
        SIGNUP_PHONE_VERIFY_SUMMARY,
        SIGNUP_PHONE_VERIFY_DESCRIPTION,
        SIGNUP_PHONE_VERIFY_400);

    assertError(
        mockMvc,
        post("/api/v1/auth/phone/signup/verify")
            .contentType(MediaType.APPLICATION_JSON)
            .content(MALFORMED_BODY),
        status().isBadRequest(),
        "MALFORMED_REQUEST");
    perform(
        post("/api/v1/auth/phone/signup/verify").contentType(MediaType.APPLICATION_JSON),
        status().isBadRequest(),
        "MALFORMED_REQUEST",
        "auth-phone-signup-verify-malformed",
        SIGNUP_PHONE_VERIFY_SUMMARY,
        SIGNUP_PHONE_VERIFY_DESCRIPTION,
        SIGNUP_PHONE_VERIFY_400);

    // ===== signup =====
    // 비밀번호 정책 위반은 Bean Validation 단계에서 걸린다 — 인증 마커가 없어도 400이 먼저 나온다.
    mockMvc
        .perform(
            post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(signupJson(UNVERIFIED_PHONE, "err-web-weak@work.example", WEAK_PASSWORD)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
        // 어느 필드가 거절됐는지는 errors[]로만 전달된다 — 비밀번호 정책은 password 필드로 특정돼야
        // 프런트가 그 입력란에 사유를 띄울 수 있다(US-1-11 입력 검증 AC).
        .andExpect(jsonPath("$.error.errors[0].field").value("password"))
        .andDo(
            errorSnippet(
                "auth-signup-invalid-input",
                ApiDocsTags.AUTH,
                WEB_SIGNUP_SUMMARY,
                WEB_SIGNUP_DESCRIPTION,
                WEB_SIGNUP_400));

    assertError(
        mockMvc,
        post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(MALFORMED_BODY),
        status().isBadRequest(),
        "MALFORMED_REQUEST");
    perform(
        post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON),
        status().isBadRequest(),
        "MALFORMED_REQUEST",
        "auth-signup-malformed",
        WEB_SIGNUP_SUMMARY,
        WEB_SIGNUP_DESCRIPTION,
        WEB_SIGNUP_400);

    // 인증 마커 없음 → 422. 게이트의 맨 앞이라 이메일 중복·번호 매칭 판정에 닿지 않는다(계정 존재 비노출).
    perform(
        post("/api/v1/auth/signup")
            .contentType(MediaType.APPLICATION_JSON)
            .content(signupJson(UNVERIFIED_PHONE, "err-web-unverified@work.example", PASSWORD)),
        status().isUnprocessableEntity(),
        "AUTH_PHONE_NOT_VERIFIED",
        "auth-signup-phone-not-verified",
        WEB_SIGNUP_SUMMARY,
        WEB_SIGNUP_DESCRIPTION,
        WEB_SIGNUP_422);

    // 필수 약관 미동의 → 422. @NotNull은 존재만 강제하고 false는 비즈니스 규칙 위반이라 400이 아니다.
    verifyPhone(AGREEMENT_PHONE);
    perform(
        post("/api/v1/auth/signup")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                signupJson(
                    AGREEMENT_PHONE, "err-web-agreement@work.example", PASSWORD, false, true)),
        status().isUnprocessableEntity(),
        "AUTH_REQUIRED_AGREEMENT_MISSING",
        "auth-signup-agreement-missing",
        WEB_SIGNUP_SUMMARY,
        WEB_SIGNUP_DESCRIPTION,
        WEB_SIGNUP_422);

    // 웹 로그인 ID 중복 → 409. 번호는 처음 보는 번호라 연동 판정이 아니라 이메일 유일성에서 걸린다.
    verifyPhone(DUPLICATE_EMAIL_PHONE);
    perform(
        post("/api/v1/auth/signup")
            .contentType(MediaType.APPLICATION_JSON)
            .content(signupJson(DUPLICATE_EMAIL_PHONE, EXISTING_EMAIL, PASSWORD)),
        status().isConflict(),
        "AUTH_EMAIL_ALREADY_REGISTERED",
        "auth-signup-email-already-registered",
        WEB_SIGNUP_SUMMARY,
        WEB_SIGNUP_DESCRIPTION,
        WEB_SIGNUP_409);

    // 번호로 매칭된 계정에 이미 웹 자격증명이 있음 → 409(로그인으로 유도). 이메일은 새 값이라 중복 게이트는 통과한다 —
    // linked=true 성공과 같은 조회의 다른 가지다.
    verifyPhone(EXISTING_PHONE);
    perform(
        post("/api/v1/auth/signup")
            .contentType(MediaType.APPLICATION_JSON)
            .content(signupJson(EXISTING_PHONE, "err-web-second@work.example", PASSWORD)),
        status().isConflict(),
        "AUTH_WEB_ACCOUNT_ALREADY_EXISTS",
        "auth-signup-web-account-already-exists",
        WEB_SIGNUP_SUMMARY,
        WEB_SIGNUP_DESCRIPTION,
        WEB_SIGNUP_409);

    // ===== login =====
    perform(
        post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(loginJson("", "")),
        status().isBadRequest(),
        "INVALID_INPUT",
        "auth-login-invalid-input",
        WEB_LOGIN_SUMMARY,
        WEB_LOGIN_DESCRIPTION,
        WEB_LOGIN_400);

    assertError(
        mockMvc,
        post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(MALFORMED_BODY),
        status().isBadRequest(),
        "MALFORMED_REQUEST");
    perform(
        post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON),
        status().isBadRequest(),
        "MALFORMED_REQUEST",
        "auth-login-malformed",
        WEB_LOGIN_SUMMARY,
        WEB_LOGIN_DESCRIPTION,
        WEB_LOGIN_400);

    // 등록되지 않은 이메일 — 코드·status·문구는 비밀번호 불일치와 같고 error.details만 없다.
    // 필드 기술자를 optional로 낮춘 만큼 부재를 단정으로 되메운다(ADR-0017).
    performWith401Fields(
        post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(loginJson("err-web-nobody@work.example", PASSWORD)),
        "auth-login-invalid-credentials",
        jsonPath("$.error.details").doesNotExist());

    // 비밀번호 불일치 — 누적 실패 횟수와 상한이 실린다. 위 401과 같은 (path, method, status)라
    // 반드시 같은 필드 기술자를 쓴다(dedup·last-wins).
    verifyPhone(WRONG_PASSWORD_PHONE);
    signup(WRONG_PASSWORD_PHONE, WRONG_PASSWORD_EMAIL, PASSWORD).andExpect(status().isOk());
    performWith401Fields(
        post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(loginJson(WRONG_PASSWORD_EMAIL, WRONG_PASSWORD)),
        "auth-login-wrong-password",
        jsonPath("$.error.details.failedAttempts").value(1),
        jsonPath("$.error.details.maxFailedAttempts")
            .value(authProperties.getWeb().getLoginMaxFailedAttempts()));

    // 연속 실패 상한까지 틀린 뒤 → 맞는 비밀번호로도 423. 잠금 판정이 자격증명 대조보다 먼저다.
    // 상한+1회를 같은 이메일로 보내므로 이메일 시도 한도가 상한보다 커야 이 시나리오가 성립한다
    // (한도 이하면 423 대신 429가 나온다 — 두 설정값은 함께 움직인다).
    verifyPhone(LOCKED_PHONE);
    signup(LOCKED_PHONE, LOCKED_EMAIL, PASSWORD).andExpect(status().isOk());
    for (int i = 0; i < authProperties.getWeb().getLoginMaxFailedAttempts(); i++) {
      mockMvc
          .perform(
              post("/api/v1/auth/login")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(loginJson(LOCKED_EMAIL, WRONG_PASSWORD)))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_CREDENTIALS"))
          .andExpect(jsonPath("$.error.details.failedAttempts").value(i + 1));
    }
    perform(
        post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(loginJson(LOCKED_EMAIL, PASSWORD)),
        status().isLocked(),
        "AUTH_ACCOUNT_LOCKED",
        "auth-login-account-locked",
        WEB_LOGIN_SUMMARY,
        WEB_LOGIN_DESCRIPTION,
        WEB_LOGIN_423);

    // 이메일 축 한도 초과 → 429. 계정을 만들지 않은 이메일로 두들겨도 걸린다는 것이 계약이다 —
    // 한도는 자격증명 조회보다 먼저 판정되므로 존재 여부와 무관하다.
    for (int i = 0; i < authProperties.getWeb().getLogin().getEmailMaxPerHour(); i++) {
      mockMvc
          .perform(
              post("/api/v1/auth/login")
                  .header("X-Forwarded-For", RATE_LIMIT_TEST_IP)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(loginJson(RATE_LIMITED_EMAIL, PASSWORD)))
          .andExpect(status().isUnauthorized());
    }
    perform(
        post("/api/v1/auth/login")
            .header("X-Forwarded-For", RATE_LIMIT_TEST_IP)
            .contentType(MediaType.APPLICATION_JSON)
            .content(loginJson(RATE_LIMITED_EMAIL, PASSWORD)),
        status().isTooManyRequests(),
        "TOO_MANY_REQUESTS",
        "auth-login-rate-limited",
        WEB_LOGIN_SUMMARY,
        WEB_LOGIN_DESCRIPTION,
        WEB_LOGIN_429);
  }

  // ---- helpers ----

  /**
   * refresh 쿠키 계약(ADR-0048) — 가입·로그인 응답이 <b>같은 속성</b>으로 내려야 브라우저가 같은 쿠키로 보고 재발급·로그아웃이 이어진다. 본문에
   * {@code refreshToken}이 없다는 단정이 이 묶음의 핵심이다.
   */
  private ResultMatcher[] refreshCookieContract() {
    return new ResultMatcher[] {
      header().string(HttpHeaders.SET_COOKIE, startsWith("refreshToken=rt_")),
      header().string(HttpHeaders.SET_COOKIE, containsString("HttpOnly")),
      header().string(HttpHeaders.SET_COOKIE, containsString("SameSite=Lax")),
      header().string(HttpHeaders.SET_COOKIE, containsString("Path=/api/v1/auth")),
      // local 프로파일만 false로 내린다 — 기본값이 뒤집히면 평문 구간에 refresh가 실린다.
      header().string(HttpHeaders.SET_COOKIE, containsString("Secure")),
      // Max-Age는 앱 refresh TTL(14일)을 그대로 따른다 — 쿠키 만료와 서버 TTL이 갈리면 진짜 만료가 두 벌이 된다.
      header()
          .string(
              HttpHeaders.SET_COOKIE,
              containsString("Max-Age=" + authProperties.getRefreshTtlSeconds())),
      jsonPath("$.data.refreshToken").doesNotExist()
    };
  }

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

  /**
   * 웹 로그인 401 스니펫 1건. {@code error.details}가 실리는 케이스와 아닌 케이스가 <b>같은 기술자 한 벌</b>을 쓰도록 묶는다 — 응답 스키마는
   * {@code (path, type)} dedup·last-wins라 한쪽만 상세를 선언하면 승자가 파일 순회 순서에 좌우된다.
   */
  private void performWith401Fields(
      MockHttpServletRequestBuilder request, String identifier, ResultMatcher... extraMatchers)
      throws Exception {
    ResultActions actions =
        mockMvc
            .perform(request)
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_CREDENTIALS"));
    for (ResultMatcher matcher : extraMatchers) {
      actions.andExpect(matcher);
    }
    actions.andDo(
        errorSnippet(
            identifier,
            ApiDocsTags.AUTH,
            WEB_LOGIN_SUMMARY,
            WEB_LOGIN_DESCRIPTION,
            AuthDocsFields.webLogin401Fields()));
  }

  /** 가입용 SMS 인증 완주(발송 → 확인) — 가입 제출이 소비할 검증 마커를 남긴다. */
  private void verifyPhone(String phoneNumber) throws Exception {
    mockMvc
        .perform(
            post("/api/v1/auth/phone/signup/verification-code")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phoneNumber\":\"" + phoneNumber + "\"}"))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            post("/api/v1/auth/phone/signup/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(verifyJson(phoneNumber, sentCodes.get(phoneNumber))))
        .andExpect(status().isOk());
  }

  private ResultActions signup(String phoneNumber, String email, String password) throws Exception {
    return mockMvc.perform(
        post("/api/v1/auth/signup")
            .contentType(MediaType.APPLICATION_JSON)
            .content(signupJson(phoneNumber, email, password)));
  }

  private static String verifyJson(String phoneNumber, String code) {
    return "{\"phoneNumber\":\"" + phoneNumber + "\",\"code\":\"" + code + "\"}";
  }

  private static String loginJson(String email, String password) {
    return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
  }

  private static String signupJson(String phoneNumber, String email, String password) {
    return signupJson(phoneNumber, email, password, true, true);
  }

  private static String signupJson(
      String phoneNumber,
      String email,
      String password,
      boolean termsOfServiceAgreed,
      boolean privacyPolicyAgreed) {
    return """
        {
          "name": "%s",
          "birthDate": "%s",
          "phoneNumber": "%s",
          "email": "%s",
          "password": "%s",
          "termsOfServiceAgreed": %s,
          "privacyPolicyAgreed": %s,
          "marketingAgreed": false
        }
        """
        .formatted(
            NAME,
            BIRTH_DATE,
            phoneNumber,
            email,
            password,
            termsOfServiceAgreed,
            privacyPolicyAgreed);
  }
}
