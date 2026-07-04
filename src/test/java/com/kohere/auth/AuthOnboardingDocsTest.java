package com.kohere.auth;

import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.document;
import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.resourceDetails;
import static com.epages.restdocs.apispec.ResourceDocumentation.resource;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.requestFields;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.epages.restdocs.apispec.ResourceSnippetParameters;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kohere.TestcontainersConfiguration;
import com.kohere.auth.application.EmailVerificationProperties;
import com.kohere.auth.domain.AppleAuthClient;
import com.kohere.auth.domain.EmailDispatchException;
import com.kohere.auth.domain.InvalidSocialTokenException;
import com.kohere.auth.domain.OidcTokenVerifier;
import com.kohere.auth.domain.OidcUser;
import com.kohere.auth.domain.VerificationEmailSender;
import com.kohere.common.security.JwtProperties;
import com.kohere.common.security.JwtTokenService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.restdocs.RestDocumentationContextProvider;
import org.springframework.restdocs.RestDocumentationExtension;
import org.springframework.restdocs.mockmvc.RestDocumentationResultHandler;
import org.springframework.restdocs.payload.FieldDescriptor;
import org.springframework.restdocs.payload.JsonFieldType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Spring REST Docs 스니펫 생성 테스트(ADR-0007). auth-onboarding 엔드포인트(소셜 로그인·약관 동의·이메일
 * 인증·온보딩·재발급·로그아웃·프로필·탈퇴)의 성공 응답과, 스펙(01-auth-onboarding.md)에 정의된 주요 에러 응답을 {@code
 * build/generated-snippets}에 생성한다(소셜 OIDC만 가짜 주입, 메일 발송은 모킹, Security·JPA·Redis·JWT는 실제 구동).
 *
 * <p>흐름: 소셜로그인(PENDING) → 약관 동의(TERMS_AGREED) → 이메일 인증(코드 발송·확인) → 온보딩(ACTIVE). 메일 발송은 {@link
 * VerificationEmailSender}를 모킹해 인증번호를 캡처한다.
 */
@SpringBootTest
@ExtendWith(RestDocumentationExtension.class)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AuthOnboardingDocsTest {

  private static final String INVALID_SOCIAL_TOKEN = "invalid-social-token";
  private static final String MALFORMED_BODY = "{ \"oops\" }";

  // 서명이 깨진(다른 키로 서명) 액세스 토큰. 서버 검증에서 401 UNAUTHENTICATED 를 유발하면서도 구조상 JWT 라,
  // restdocs-api-spec 이 "무인증" 예시에서도 bearerAuthJWT 보안 스킴을 도출하게 한다. 무인증 예시는 본래 헤더를
  // 안 보내는데, 그 예시가 오퍼레이션 "대표"로 뽑히면(스니펫 병합 순서는 비결정적) security 가 통째로 누락된다 →
  // Swagger 자물쇠 사라지고 토큰 미전송 → 401. 모든 예시가 Bearer JWT 헤더를 갖게 해 순서와 무관하게 막는다.
  private static final String FORGED_TOKEN =
      Jwts.builder()
          .issuer("kohere")
          .subject("1")
          .claim("onboardingCompleted", true)
          .signWith(
              Keys.hmacShaKeyFor(
                  "forged-doc-only-wrong-secret-please-override-32bytes-min!!"
                      .getBytes(StandardCharsets.UTF_8)))
          .compact();

  @TestConfiguration
  static class FakeOidcConfig {
    @Bean
    @Primary
    OidcTokenVerifier fakeOidcTokenVerifier() {
      return (provider, idToken) -> {
        if (INVALID_SOCIAL_TOKEN.equals(idToken)) {
          throw new InvalidSocialTokenException();
        }
        return new OidcUser(provider, idToken, idToken + "@example.com");
      };
    }
  }

  @Autowired private WebApplicationContext context;
  @Autowired private JwtTokenService jwtTokenService;
  @Autowired private JwtProperties jwtProperties;
  @Autowired private EmailVerificationProperties emailProperties;
  @MockitoBean private VerificationEmailSender emailSender;
  @MockitoBean private AppleAuthClient appleAuthClient; // 실제 Apple HTTP 대체
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final Map<String, String> sentCodes = new ConcurrentHashMap<>();
  private MockMvc mockMvc;

  @BeforeEach
  void setUp(RestDocumentationContextProvider restDocumentation) {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .apply(springSecurity())
            .apply(documentationConfiguration(restDocumentation))
            .build();
    // 발송 인증번호를 이메일별로 기록(검증 단계에서 사용). 실제 메일 발송은 하지 않는다.
    doAnswer(
            inv -> {
              sentCodes.put(inv.getArgument(0), inv.getArgument(1));
              return null;
            })
        .when(emailSender)
        .send(any(), any());
  }

  @Test
  void generatesAuthOnboardingSnippets() throws Exception {
    String email = emailFor("docs-sub-1");
    when(appleAuthClient.exchangeAuthorizationCode("docs-apple-code"))
        .thenReturn(new AppleAuthClient.AppleTokens("docs-apple-sub", "docs-apple-rt"));

    // 소셜 로그인(신규) → 온보딩 임시 토큰
    String login =
        mockMvc
            .perform(
                post("/api/v1/auth/social-login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"provider\":\"GOOGLE\",\"idToken\":\"docs-sub-1\"}"))
            .andExpect(status().isOk())
            .andDo(
                document(
                    "auth-social-login",
                    resourceDetails().summary("소셜 로그인 — 자격 검증 후 서버 토큰 발급(status로 재개 지점 분기)"),
                    requestFields(socialLoginRequestFields()),
                    responseFields(socialLoginResponseFields())))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String onboardingToken = read(login, "data", "accessToken");

    // 소셜 로그인(Apple, 신규) — authorizationCode 교환 후 동일 응답 형태(요청 바디만 provider별 분기)
    mockMvc
        .perform(
            post("/api/v1/auth/social-login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\":\"APPLE\",\"authorizationCode\":\"docs-apple-code\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("PENDING"))
        .andDo(
            document(
                "auth-social-login-apple",
                resourceDetails()
                    .summary("소셜 로그인(Apple) — authorizationCode 교환·id_token 검증 후 토큰 발급"),
                requestFields(socialLoginRequestFields()),
                responseFields(socialLoginResponseFields())));

    // 약관 동의 → TERMS_AGREED
    mockMvc
        .perform(
            post("/api/v1/auth/terms")
                .header(HttpHeaders.AUTHORIZATION, bearer(onboardingToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(termsJson(true, true, false)))
        .andExpect(status().isOk())
        .andDo(
            document(
                "auth-terms",
                resourceDetails().summary("약관 동의 — PENDING→TERMS_AGREED 전이, 동의·약관버전 기록"),
                requestFields(termsRequestFields()),
                responseFields(termsResponseFields())));

    // 이메일 인증번호 발송
    mockMvc
        .perform(
            post("/api/v1/auth/email/verification-code")
                .header(HttpHeaders.AUTHORIZATION, bearer(onboardingToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\"}"))
        .andExpect(status().isOk())
        .andDo(
            document(
                "auth-email-verification-code",
                resourceDetails().summary("이메일 인증번호 발송 — 동기 발송 성공 후 챌린지 저장(email 마스킹 반환)"),
                requestFields(emailCodeRequestFields()),
                responseFields(emailCodeResponseFields())));

    // 이메일 인증번호 확인
    mockMvc
        .perform(
            post("/api/v1/auth/email/verify")
                .header(HttpHeaders.AUTHORIZATION, bearer(onboardingToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"code\":\"" + sentCodes.get(email) + "\"}"))
        .andExpect(status().isOk())
        .andDo(
            document(
                "auth-email-verify",
                resourceDetails().summary("이메일 인증번호 확인 — 검증 완료(VERIFIED) 마킹"),
                requestFields(emailVerifyRequestFields()),
                responseFields(emailVerifyResponseFields())));

    // 온보딩 완료 → 정식 토큰
    String onboarding =
        mockMvc
            .perform(
                post("/api/v1/auth/onboarding")
                    .header(HttpHeaders.AUTHORIZATION, bearer(onboardingToken))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(onboardingJson(email)))
            .andExpect(status().isOk())
            .andDo(
                document(
                    "auth-onboarding",
                    resourceDetails().summary("온보딩 제출 — TERMS_AGREED→ACTIVE 전이, 닉네임 배정·정식 토큰 발급"),
                    requestFields(onboardingRequestFields()),
                    responseFields(onboardingResponseFields())))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String accessToken = read(onboarding, "data", "accessToken");
    String refreshToken = read(onboarding, "data", "refreshToken");

    // 기존 회원(ACTIVE) 재로그인 — 정식 access+refresh 발급(status=ACTIVE, onboardingRequired=false)
    mockMvc
        .perform(
            post("/api/v1/auth/social-login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\":\"GOOGLE\",\"idToken\":\"docs-sub-1\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("ACTIVE"))
        .andExpect(jsonPath("$.data.onboardingRequired").value(false))
        .andExpect(jsonPath("$.data.refreshToken").isNotEmpty())
        .andDo(
            document(
                "auth-social-login-active",
                resourceDetails().summary("소셜 로그인 — 기존 회원(ACTIVE) 로그인: 정식 access+refresh 발급"),
                requestFields(socialLoginRequestFields()),
                responseFields(socialLoginResponseFields())));

    // 내 프로필 조회
    mockMvc
        .perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
        .andExpect(status().isOk())
        .andDo(
            document(
                "user-get-me",
                resourceDetails().summary("내 프로필 조회"),
                responseFields(profileResponseFields())));

    // 내 프로필 부분 수정
    mockMvc
        .perform(
            patch("/api/v1/users/me")
                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"marketingAgreed\":true}"))
        .andExpect(status().isOk())
        .andDo(
            document(
                "user-patch-me",
                resourceDetails().summary("내 프로필 부분 수정 — 전송한 필드만 변경"),
                requestFields(patchRequestFields()),
                responseFields(profileResponseFields())));

    // 재발급(회전)
    String reissue =
        mockMvc
            .perform(
                post("/api/v1/auth/reissue")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
            .andExpect(status().isOk())
            .andDo(
                document(
                    "auth-reissue",
                    resourceDetails().summary("토큰 재발급 — refresh로 access 재발급(항상 회전)"),
                    requestFields(
                        refreshTokenRequestField("서버가 발급·보관(해시) 중인 불투명 refresh 토큰(빈값 불가)")),
                    responseFields(
                        tokenResponseFields(
                            "새 access 토큰(JWT)", "새 refresh 토큰(회전 — 제출 토큰은 ROTATED 폐기)"))))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String newRefreshToken = read(reissue, "data", "refreshToken");

    // 로그아웃
    mockMvc
        .perform(
            post("/api/v1/auth/logout")
                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + newRefreshToken + "\"}"))
        .andExpect(status().isNoContent())
        .andDo(
            document(
                "auth-logout",
                resourceDetails().summary("로그아웃 — 제출 refresh 토큰 무효화(멱등, 204)"),
                requestFields(refreshTokenRequestField("무효화할 refresh 토큰(빈값 불가)"))));

    // 탈퇴
    mockMvc
        .perform(delete("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
        .andExpect(status().isNoContent())
        .andDo(
            document(
                "user-withdraw",
                resourceDetails().summary("회원 탈퇴 — WITHDRAWN 전이·PII 익명화·refresh 토큰 일괄 무효화")));
  }

  /** 스펙의 "발생 가능한 에러"를 엔드포인트별로 실제 트리거해 스니펫으로 생성하고 status·error.code를 단정한다. */
  @Test
  void generatesAuthOnboardingErrorSnippets() throws Exception {
    String pendingToken = read(socialLogin("err-pending"), "data", "accessToken"); // PENDING 토큰
    String activeAccess = onboardCompletely("err-active"); // 정식 ACTIVE access 토큰
    String withdrawAccess = onboardCompletely("err-withdraw"); // 탈퇴 시나리오 전용 ACTIVE 토큰

    String expiredToken = expiredAccessToken();
    String ghostToken = jwtTokenService.issueAccessToken(999_999_999L);

    // ===== social-login =====
    perform(
        post("/api/v1/auth/social-login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"idToken\":\"docs-sub-1\"}"),
        status().isBadRequest(),
        "INVALID_INPUT",
        "auth-social-login-invalid-input",
        "소셜 로그인 — 입력 검증 실패 (400 INVALID_INPUT): provider 누락(@NotNull)");

    perform(
        post("/api/v1/auth/social-login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"provider\":\"GOOGLE\",\"idToken\":\"\"}"),
        status().isBadRequest(),
        "AUTH_MISSING_CREDENTIAL",
        "auth-social-login-missing-credential",
        "소셜 로그인 — 자격 누락 (400 AUTH_MISSING_CREDENTIAL): provider별 자격(Google idToken / Apple authorizationCode) 누락/빈값");

    perform(
        post("/api/v1/auth/social-login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(MALFORMED_BODY),
        status().isBadRequest(),
        "MALFORMED_REQUEST",
        "auth-social-login-malformed",
        "소셜 로그인 — 본문 해석 불가 (400 MALFORMED_REQUEST)");

    perform(
        post("/api/v1/auth/social-login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"provider\":\"GOOGLE\",\"idToken\":\"" + INVALID_SOCIAL_TOKEN + "\"}"),
        status().isUnauthorized(),
        "AUTH_INVALID_SOCIAL_TOKEN",
        "auth-social-login-invalid-token",
        "소셜 로그인 — 소셜 토큰 검증 실패 (401 AUTH_INVALID_SOCIAL_TOKEN)");

    // ===== terms =====
    perform(
        post("/api/v1/auth/terms")
            .header(HttpHeaders.AUTHORIZATION, bearer(pendingToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content(termsJson(false, true, false)),
        status().isUnprocessableEntity(),
        "AUTH_REQUIRED_AGREEMENT_MISSING",
        "auth-terms-agreement-missing",
        "약관 동의 — 필수 약관 미동의 (422 AUTH_REQUIRED_AGREEMENT_MISSING)");

    perform(
        post("/api/v1/auth/terms")
            .header(HttpHeaders.AUTHORIZATION, bearer(pendingToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"marketingAgreed\":false}"),
        status().isBadRequest(),
        "INVALID_INPUT",
        "auth-terms-invalid-input",
        "약관 동의 — 입력 검증 실패 (400 INVALID_INPUT): 필수 동의 필드 누락(@NotNull)");

    perform(
        post("/api/v1/auth/terms")
            .header(HttpHeaders.AUTHORIZATION, bearer(pendingToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content(MALFORMED_BODY),
        status().isBadRequest(),
        "MALFORMED_REQUEST",
        "auth-terms-malformed",
        "약관 동의 — 본문 해석 불가 (400 MALFORMED_REQUEST)");

    perform(
        post("/api/v1/auth/terms")
            .header(HttpHeaders.AUTHORIZATION, bearer(FORGED_TOKEN))
            .contentType(MediaType.APPLICATION_JSON)
            .content(termsJson(true, true, false)),
        status().isUnauthorized(),
        "UNAUTHENTICATED",
        "auth-terms-unauthenticated",
        "약관 동의 — 인증 누락/위조 (401 UNAUTHENTICATED)");

    perform(
        post("/api/v1/auth/terms")
            .header(HttpHeaders.AUTHORIZATION, bearer(expiredToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content(termsJson(true, true, false)),
        status().isUnauthorized(),
        "TOKEN_EXPIRED",
        "auth-terms-token-expired",
        "약관 동의 — 액세스 토큰 만료 (401 TOKEN_EXPIRED)");

    perform(
        post("/api/v1/auth/terms")
            .header(HttpHeaders.AUTHORIZATION, bearer(activeAccess))
            .contentType(MediaType.APPLICATION_JSON)
            .content(termsJson(true, true, false)),
        status().isConflict(),
        "AUTH_ONBOARDING_ALREADY_COMPLETED",
        "auth-terms-already-completed",
        "약관 동의 — 이미 온보딩 완료(ACTIVE) 사용자의 재요청 (409 AUTH_ONBOARDING_ALREADY_COMPLETED)");

    // ===== email verification-code =====
    perform(
        post("/api/v1/auth/email/verification-code")
            .header(HttpHeaders.AUTHORIZATION, bearer(pendingToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"\"}"),
        status().isBadRequest(),
        "INVALID_INPUT",
        "auth-email-verification-code-invalid-input",
        "이메일 인증번호 발송 — 입력 검증 실패 (400 INVALID_INPUT): email 누락/빈값/형식");

    perform(
        post("/api/v1/auth/email/verification-code")
            .header(HttpHeaders.AUTHORIZATION, bearer(pendingToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content(MALFORMED_BODY),
        status().isBadRequest(),
        "MALFORMED_REQUEST",
        "auth-email-verification-code-malformed",
        "이메일 인증번호 발송 — 본문 해석 불가 (400 MALFORMED_REQUEST)");

    perform(
        post("/api/v1/auth/email/verification-code")
            .header(HttpHeaders.AUTHORIZATION, bearer(FORGED_TOKEN))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + emailFor("err-pending") + "\"}"),
        status().isUnauthorized(),
        "UNAUTHENTICATED",
        "auth-email-verification-code-unauthenticated",
        "이메일 인증번호 발송 — 인증 누락/위조 (401 UNAUTHENTICATED)");

    perform(
        post("/api/v1/auth/email/verification-code")
            .header(HttpHeaders.AUTHORIZATION, bearer(expiredToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + emailFor("err-pending") + "\"}"),
        status().isUnauthorized(),
        "TOKEN_EXPIRED",
        "auth-email-verification-code-token-expired",
        "이메일 인증번호 발송 — 액세스 토큰 만료 (401 TOKEN_EXPIRED)");

    // 약관 미동의(PENDING) 상태로 인증번호 발송 → 약관 동의 선행 안내 422 (이메일 인증은 약관 동의가 선행)
    perform(
        post("/api/v1/auth/email/verification-code")
            .header(HttpHeaders.AUTHORIZATION, bearer(pendingToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + emailFor("err-pending") + "\"}"),
        status().isUnprocessableEntity(),
        "AUTH_TERMS_AGREEMENT_REQUIRED",
        "auth-email-verification-code-terms-required",
        "이메일 인증번호 발송 — 약관 미동의(PENDING) 상태 (422 AUTH_TERMS_AGREEMENT_REQUIRED)");

    // 재발송 간격 미달 → 429 (첫 발송 성공 직후 즉시 재요청)
    String resendToken = read(socialLogin("err-resend"), "data", "accessToken");
    agreeTerms(resendToken);
    mockMvc
        .perform(
            post("/api/v1/auth/email/verification-code")
                .header(HttpHeaders.AUTHORIZATION, bearer(resendToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + emailFor("err-resend") + "\"}"))
        .andExpect(status().isOk());
    perform(
        post("/api/v1/auth/email/verification-code")
            .header(HttpHeaders.AUTHORIZATION, bearer(resendToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + emailFor("err-resend") + "\"}"),
        status().isTooManyRequests(),
        "TOO_MANY_REQUESTS",
        "auth-email-verification-code-rate-limited",
        "이메일 인증번호 발송 — 재발송 간격 미달 (429 TOO_MANY_REQUESTS)");

    // 메일 발송 실패(provider 장애·타임아웃) → 502, 챌린지 미저장
    String smtpToken = read(socialLogin("err-smtp"), "data", "accessToken");
    agreeTerms(smtpToken);
    String smtpEmail = emailFor("err-smtp");
    doThrow(new EmailDispatchException(new RuntimeException("smtp down")))
        .when(emailSender)
        .send(eq(smtpEmail), any());
    perform(
        post("/api/v1/auth/email/verification-code")
            .header(HttpHeaders.AUTHORIZATION, bearer(smtpToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + smtpEmail + "\"}"),
        status().isBadGateway(),
        "UPSTREAM_ERROR",
        "auth-email-verification-code-dispatch-failed",
        "이메일 인증번호 발송 — 메일 발송 실패 (502 UPSTREAM_ERROR): 챌린지 미저장");

    perform(
        post("/api/v1/auth/email/verification-code")
            .header(HttpHeaders.AUTHORIZATION, bearer(activeAccess))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + emailFor("err-active") + "\"}"),
        status().isConflict(),
        "AUTH_ONBOARDING_ALREADY_COMPLETED",
        "auth-email-verification-code-already-completed",
        "이메일 인증번호 발송 — 이미 온보딩 완료(ACTIVE) 사용자의 요청 (409 AUTH_ONBOARDING_ALREADY_COMPLETED)");

    // ===== email verify =====
    perform(
        post("/api/v1/auth/email/verify")
            .header(HttpHeaders.AUTHORIZATION, bearer(pendingToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + emailFor("err-pending") + "\",\"code\":\"000000\"}"),
        status().isUnprocessableEntity(),
        "AUTH_EMAIL_VERIFICATION_FAILED",
        "auth-email-verify-failed",
        "이메일 인증 — 챌린지 부재/만료/불일치 (422 AUTH_EMAIL_VERIFICATION_FAILED)");

    perform(
        post("/api/v1/auth/email/verify")
            .header(HttpHeaders.AUTHORIZATION, bearer(pendingToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"\",\"code\":\"123456\"}"),
        status().isBadRequest(),
        "INVALID_INPUT",
        "auth-email-verify-invalid-input",
        "이메일 인증 확인 — 입력 검증 실패 (400 INVALID_INPUT): email/code 누락·빈값");

    perform(
        post("/api/v1/auth/email/verify")
            .header(HttpHeaders.AUTHORIZATION, bearer(pendingToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content(MALFORMED_BODY),
        status().isBadRequest(),
        "MALFORMED_REQUEST",
        "auth-email-verify-malformed",
        "이메일 인증 확인 — 본문 해석 불가 (400 MALFORMED_REQUEST)");

    perform(
        post("/api/v1/auth/email/verify")
            .header(HttpHeaders.AUTHORIZATION, bearer(FORGED_TOKEN))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + emailFor("err-pending") + "\",\"code\":\"000000\"}"),
        status().isUnauthorized(),
        "UNAUTHENTICATED",
        "auth-email-verify-unauthenticated",
        "이메일 인증 확인 — 인증 누락/위조 (401 UNAUTHENTICATED)");

    perform(
        post("/api/v1/auth/email/verify")
            .header(HttpHeaders.AUTHORIZATION, bearer(expiredToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + emailFor("err-pending") + "\",\"code\":\"000000\"}"),
        status().isUnauthorized(),
        "TOKEN_EXPIRED",
        "auth-email-verify-token-expired",
        "이메일 인증 확인 — 액세스 토큰 만료 (401 TOKEN_EXPIRED)");

    // 코드 불일치 누적 → 시도 상한 초과 429 (오입력 maxAttempts회째에 거절)
    String attemptsToken = read(socialLogin("err-attempts"), "data", "accessToken");
    agreeTerms(attemptsToken);
    String attemptsEmail = emailFor("err-attempts");
    mockMvc
        .perform(
            post("/api/v1/auth/email/verification-code")
                .header(HttpHeaders.AUTHORIZATION, bearer(attemptsToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + attemptsEmail + "\"}"))
        .andExpect(status().isOk());
    for (int i = 0; i < emailProperties.getMaxAttempts() - 1; i++) {
      mockMvc
          .perform(
              post("/api/v1/auth/email/verify")
                  .header(HttpHeaders.AUTHORIZATION, bearer(attemptsToken))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"email\":\"" + attemptsEmail + "\",\"code\":\"000000\"}"))
          .andExpect(status().isUnprocessableEntity());
    }
    perform(
        post("/api/v1/auth/email/verify")
            .header(HttpHeaders.AUTHORIZATION, bearer(attemptsToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + attemptsEmail + "\",\"code\":\"000000\"}"),
        status().isTooManyRequests(),
        "TOO_MANY_REQUESTS",
        "auth-email-verify-rate-limited",
        "이메일 인증 확인 — 코드 불일치 누적·시도 상한 초과 (429 TOO_MANY_REQUESTS)");

    // ===== onboarding =====
    perform(
        post("/api/v1/auth/onboarding")
            .header(HttpHeaders.AUTHORIZATION, bearer(pendingToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content(onboardingBlankFirstName()),
        status().isBadRequest(),
        "INVALID_INPUT",
        "auth-onboarding-invalid-input",
        "온보딩 — 입력 검증 실패 (400 INVALID_INPUT): 필수 필드 누락/형식 위반");

    perform(
        post("/api/v1/auth/onboarding")
            .header(HttpHeaders.AUTHORIZATION, bearer(pendingToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content(MALFORMED_BODY),
        status().isBadRequest(),
        "MALFORMED_REQUEST",
        "auth-onboarding-malformed",
        "온보딩 — 본문 해석 불가 (400 MALFORMED_REQUEST)");

    perform(
        post("/api/v1/auth/onboarding")
            .header(HttpHeaders.AUTHORIZATION, bearer(FORGED_TOKEN))
            .contentType(MediaType.APPLICATION_JSON)
            .content(onboardingJson(emailFor("err-pending"))),
        status().isUnauthorized(),
        "UNAUTHENTICATED",
        "auth-onboarding-unauthenticated",
        "온보딩 — 인증 누락/위조 (401 UNAUTHENTICATED)");

    perform(
        post("/api/v1/auth/onboarding")
            .header(HttpHeaders.AUTHORIZATION, bearer(expiredToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content(onboardingJson(emailFor("err-pending"))),
        status().isUnauthorized(),
        "TOKEN_EXPIRED",
        "auth-onboarding-token-expired",
        "온보딩 — 액세스 토큰 만료 (401 TOKEN_EXPIRED)");

    // 약관 미동의(PENDING) 상태로 온보딩 제출 → 약관 동의 선행 안내 422 (이메일 인증 안내보다 약관 동의가 먼저)
    perform(
        post("/api/v1/auth/onboarding")
            .header(HttpHeaders.AUTHORIZATION, bearer(pendingToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content(onboardingJson(emailFor("err-pending"))),
        status().isUnprocessableEntity(),
        "AUTH_TERMS_AGREEMENT_REQUIRED",
        "auth-onboarding-terms-required",
        "온보딩 — 약관 미동의 상태 (422 AUTH_TERMS_AGREEMENT_REQUIRED)");

    // 약관은 동의했으나(TERMS_AGREED) 이메일 미인증 → 이메일 인증 선행 필요 422
    String emailNeedToken = read(socialLogin("err-emailneed"), "data", "accessToken");
    agreeTerms(emailNeedToken);
    perform(
        post("/api/v1/auth/onboarding")
            .header(HttpHeaders.AUTHORIZATION, bearer(emailNeedToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content(onboardingJson(emailFor("err-emailneed"))),
        status().isUnprocessableEntity(),
        "AUTH_EMAIL_NOT_VERIFIED",
        "auth-onboarding-email-not-verified",
        "온보딩 — 약관 동의 후 이메일 미인증 (422 AUTH_EMAIL_NOT_VERIFIED)");

    // 이미 온보딩 완료한 사용자(약관·이메일 모두 통과) 재요청 → 409
    perform(
        post("/api/v1/auth/onboarding")
            .header(HttpHeaders.AUTHORIZATION, bearer(activeAccess))
            .contentType(MediaType.APPLICATION_JSON)
            .content(onboardingJson(emailFor("err-active"))),
        status().isConflict(),
        "AUTH_ONBOARDING_ALREADY_COMPLETED",
        "auth-onboarding-already-completed",
        "온보딩 — 이미 완료한 사용자의 재요청 (409 AUTH_ONBOARDING_ALREADY_COMPLETED)");

    // ===== reissue =====
    perform(
        post("/api/v1/auth/reissue")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"refreshToken\":\"\"}"),
        status().isBadRequest(),
        "INVALID_INPUT",
        "auth-reissue-invalid-input",
        "재발급 — 입력 검증 실패 (400 INVALID_INPUT): refreshToken 누락/빈값");

    perform(
        post("/api/v1/auth/reissue")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"refreshToken\":\"rt_does_not_exist\"}"),
        status().isUnauthorized(),
        "AUTH_INVALID_REFRESH_TOKEN",
        "auth-reissue-invalid-token",
        "재발급 — refresh 토큰 만료/위조/무효화/재사용 탐지 (401 AUTH_INVALID_REFRESH_TOKEN)");

    perform(
        post("/api/v1/auth/reissue")
            .contentType(MediaType.APPLICATION_JSON)
            .content(MALFORMED_BODY),
        status().isBadRequest(),
        "MALFORMED_REQUEST",
        "auth-reissue-malformed",
        "재발급 — 본문 해석 불가 (400 MALFORMED_REQUEST)");

    // ===== logout =====
    perform(
        post("/api/v1/auth/logout")
            .header(HttpHeaders.AUTHORIZATION, bearer(FORGED_TOKEN))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"refreshToken\":\"rt_any\"}"),
        status().isUnauthorized(),
        "UNAUTHENTICATED",
        "auth-logout-unauthenticated",
        "로그아웃 — 인증 누락/위조 (401 UNAUTHENTICATED)");

    perform(
        post("/api/v1/auth/logout")
            .header(HttpHeaders.AUTHORIZATION, bearer(pendingToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"refreshToken\":\"rt_any\"}"),
        status().isForbidden(),
        "AUTH_ONBOARDING_REQUIRED",
        "auth-logout-onboarding-required",
        "로그아웃 — 온보딩 미완료(PENDING/TERMS_AGREED) 토큰 접근 (403 AUTH_ONBOARDING_REQUIRED)");

    perform(
        post("/api/v1/auth/logout")
            .header(HttpHeaders.AUTHORIZATION, bearer(activeAccess))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"refreshToken\":\"\"}"),
        status().isBadRequest(),
        "INVALID_INPUT",
        "auth-logout-invalid-input",
        "로그아웃 — 입력 검증 실패 (400 INVALID_INPUT): refreshToken 누락/빈값");

    perform(
        post("/api/v1/auth/logout")
            .header(HttpHeaders.AUTHORIZATION, bearer(activeAccess))
            .contentType(MediaType.APPLICATION_JSON)
            .content(MALFORMED_BODY),
        status().isBadRequest(),
        "MALFORMED_REQUEST",
        "auth-logout-malformed",
        "로그아웃 — 본문 해석 불가 (400 MALFORMED_REQUEST)");

    perform(
        post("/api/v1/auth/logout")
            .header(HttpHeaders.AUTHORIZATION, bearer(expiredToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"refreshToken\":\"rt_any\"}"),
        status().isUnauthorized(),
        "TOKEN_EXPIRED",
        "auth-logout-token-expired",
        "로그아웃 — 액세스 토큰 만료 (401 TOKEN_EXPIRED)");

    // ===== GET /users/me =====
    perform(
        get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, bearer(FORGED_TOKEN)),
        status().isUnauthorized(),
        "UNAUTHENTICATED",
        "user-get-me-unauthenticated",
        "내 프로필 조회 — 인증 누락/위조 (401 UNAUTHENTICATED)");

    perform(
        get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, bearer(pendingToken)),
        status().isForbidden(),
        "AUTH_ONBOARDING_REQUIRED",
        "user-get-me-onboarding-required",
        "내 프로필 조회 — 온보딩 미완료(PENDING/TERMS_AGREED) 토큰 접근 (403 AUTH_ONBOARDING_REQUIRED)");

    perform(
        get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, bearer(expiredToken)),
        status().isUnauthorized(),
        "TOKEN_EXPIRED",
        "user-get-me-token-expired",
        "내 프로필 조회 — 액세스 토큰 만료 (401 TOKEN_EXPIRED)");

    // ===== PATCH /users/me =====
    perform(
        patch("/api/v1/users/me")
            .header(HttpHeaders.AUTHORIZATION, bearer(activeAccess))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"birthDate\":\"2999-01-01\"}"),
        status().isBadRequest(),
        "INVALID_INPUT",
        "user-patch-me-invalid-input",
        "내 프로필 수정 — 입력 검증 실패 (400 INVALID_INPUT): birthDate 미래 등");

    perform(
        patch("/api/v1/users/me")
            .header(HttpHeaders.AUTHORIZATION, bearer(activeAccess))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"gender\":\"UNKNOWN\"}"),
        status().isBadRequest(),
        "MALFORMED_REQUEST",
        "user-patch-me-malformed",
        "내 프로필 수정 — 본문 해석 불가 (400 MALFORMED_REQUEST): enum 매핑 실패 등");

    perform(
        patch("/api/v1/users/me")
            .header(HttpHeaders.AUTHORIZATION, bearer(FORGED_TOKEN))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"marketingAgreed\":true}"),
        status().isUnauthorized(),
        "UNAUTHENTICATED",
        "user-patch-me-unauthenticated",
        "내 프로필 수정 — 인증 누락/위조 (401 UNAUTHENTICATED)");

    perform(
        patch("/api/v1/users/me")
            .header(HttpHeaders.AUTHORIZATION, bearer(expiredToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"marketingAgreed\":true}"),
        status().isUnauthorized(),
        "TOKEN_EXPIRED",
        "user-patch-me-token-expired",
        "내 프로필 수정 — 액세스 토큰 만료 (401 TOKEN_EXPIRED)");

    perform(
        patch("/api/v1/users/me")
            .header(HttpHeaders.AUTHORIZATION, bearer(pendingToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"marketingAgreed\":true}"),
        status().isForbidden(),
        "AUTH_ONBOARDING_REQUIRED",
        "user-patch-me-onboarding-required",
        "내 프로필 수정 — 온보딩 미완료(PENDING/TERMS_AGREED) 토큰 접근 (403 AUTH_ONBOARDING_REQUIRED)");

    perform(
        patch("/api/v1/users/me")
            .header(HttpHeaders.AUTHORIZATION, bearer(ghostToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"marketingAgreed\":true}"),
        status().isNotFound(),
        "USER_NOT_FOUND",
        "user-patch-me-not-found",
        "내 프로필 수정 — 대상 사용자 없음 (404 USER_NOT_FOUND)");

    // ===== DELETE /users/me =====
    perform(
        delete("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, bearer(FORGED_TOKEN)),
        status().isUnauthorized(),
        "UNAUTHENTICATED",
        "user-withdraw-unauthenticated",
        "회원 탈퇴 — 인증 누락/위조 (401 UNAUTHENTICATED)");

    perform(
        delete("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, bearer(expiredToken)),
        status().isUnauthorized(),
        "TOKEN_EXPIRED",
        "user-withdraw-token-expired",
        "회원 탈퇴 — 액세스 토큰 만료 (401 TOKEN_EXPIRED)");

    perform(
        delete("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, bearer(ghostToken)),
        status().isNotFound(),
        "USER_NOT_FOUND",
        "user-withdraw-not-found",
        "회원 탈퇴 — 대상 사용자 없음 (404 USER_NOT_FOUND)");

    mockMvc
        .perform(
            delete("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, bearer(withdrawAccess)))
        .andExpect(status().isNoContent());

    perform(
        get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, bearer(withdrawAccess)),
        status().isNotFound(),
        "USER_NOT_FOUND",
        "user-get-me-not-found",
        "내 프로필 조회 — 탈퇴/삭제로 사용자 없음 (404 USER_NOT_FOUND)");

    perform(
        delete("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, bearer(withdrawAccess)),
        status().isConflict(),
        "USER_ALREADY_WITHDRAWN",
        "user-withdraw-already-withdrawn",
        "회원 탈퇴 — 이미 탈퇴한 사용자의 재요청 (409 USER_ALREADY_WITHDRAWN)");
  }

  // ---- helpers ----

  private void perform(
      org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
      org.springframework.test.web.servlet.ResultMatcher expectedStatus,
      String expectedCode,
      String identifier,
      String summary)
      throws Exception {
    mockMvc
        .perform(request)
        .andExpect(expectedStatus)
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.error.code").value(expectedCode))
        .andDo(errorSnippet(identifier, summary));
  }

  private static RestDocumentationResultHandler errorSnippet(String identifier, String summary) {
    return document(
        identifier,
        resource(
            ResourceSnippetParameters.builder()
                .summary(summary)
                .description(
                    "실패 응답 — 공통 래퍼(success=false·data=null·error). 클라이언트는 error.code로 분기한다"
                        + "(error-response-guide §1·§4).")
                .responseFields(errorFields())
                .build()));
  }

  private static List<FieldDescriptor> errorFields() {
    return List.of(
        fieldWithPath("success").type(JsonFieldType.BOOLEAN).description("성공 여부 — 에러 응답은 항상 false"),
        fieldWithPath("data")
            .type(JsonFieldType.NULL)
            .optional()
            .description("에러 응답의 data는 항상 null"),
        fieldWithPath("error.code")
            .type(JsonFieldType.STRING)
            .description("에러 식별 코드(UPPER_SNAKE_CASE) — 클라이언트 분기 기준"),
        fieldWithPath("error.message")
            .type(JsonFieldType.STRING)
            .description("사람이 읽는 설명(민감정보 미포함, message로 분기 금지)"),
        fieldWithPath("error.errors")
            .type(JsonFieldType.ARRAY)
            .description("입력 검증 실패 시 필드별 상세 목록. 그 외 에러는 빈 배열"),
        fieldWithPath("error.errors[].field")
            .type(JsonFieldType.STRING)
            .optional()
            .description("검증에 실패한 요청 필드 경로(INVALID_INPUT에서만)"),
        fieldWithPath("error.errors[].reason")
            .type(JsonFieldType.STRING)
            .optional()
            .description("해당 필드의 실패 사유(INVALID_INPUT에서만)"));
  }

  // ---- 성공 응답/요청 필드 기술자 ----

  private static FieldDescriptor field(String path, JsonFieldType type, String description) {
    return fieldWithPath(path).type(type).description(description);
  }

  private static FieldDescriptor optField(String path, JsonFieldType type, String description) {
    return fieldWithPath(path).type(type).optional().description(description);
  }

  private static FieldDescriptor errorNull() {
    return fieldWithPath("error")
        .type(JsonFieldType.NULL)
        .optional()
        .description("성공 응답의 error는 항상 null");
  }

  private static List<FieldDescriptor> socialLoginRequestFields() {
    return List.of(
        field("provider", JsonFieldType.STRING, "소셜 제공자: APPLE | GOOGLE"),
        optField("idToken", JsonFieldType.STRING, "Google OIDC ID 토큰(GOOGLE 필수, APPLE 미사용)"),
        optField(
            "authorizationCode",
            JsonFieldType.STRING,
            "Apple 1회용 인가코드(APPLE 필수, GOOGLE 미사용, 약 5분 만료)"));
  }

  private static List<FieldDescriptor> socialLoginResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"),
        field(
            "data.onboardingRequired",
            JsonFieldType.BOOLEAN,
            "온보딩 필요 여부 — 신규/온보딩 미완료=true, 기존 ACTIVE=false"),
        field(
            "data.status",
            JsonFieldType.STRING,
            "사용자 상태(PENDING|TERMS_AGREED|ACTIVE) — 클라이언트 재개 지점 분기"),
        field("data.tokenType", JsonFieldType.STRING, "토큰 타입(Bearer)"),
        field("data.accessToken", JsonFieldType.STRING, "access 토큰(JWT). 미완료는 온보딩 전용 스코프"),
        optField("data.refreshToken", JsonFieldType.STRING, "refresh 토큰(불투명). 미완료 응답에서는 null"),
        field("data.expiresIn", JsonFieldType.NUMBER, "access 토큰 만료까지 초(온보딩 1800 / 정식 3600)"),
        errorNull());
  }

  private static List<FieldDescriptor> termsRequestFields() {
    return List.of(
        field("termsOfServiceAgreed", JsonFieldType.BOOLEAN, "이용약관 동의(필수). false면 422"),
        field("privacyPolicyAgreed", JsonFieldType.BOOLEAN, "개인정보처리방침 동의(필수). false면 422"),
        optField("marketingAgreed", JsonFieldType.BOOLEAN, "마케팅 수신 동의(선택, 기본 false)"));
  }

  private static List<FieldDescriptor> termsResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"),
        field("data.status", JsonFieldType.STRING, "전이 후 상태(TERMS_AGREED)"),
        field("data.termsOfServiceAgreed", JsonFieldType.BOOLEAN, "이용약관 동의 여부"),
        field("data.privacyPolicyAgreed", JsonFieldType.BOOLEAN, "개인정보처리방침 동의 여부"),
        field("data.marketingAgreed", JsonFieldType.BOOLEAN, "마케팅 수신 동의 여부"),
        field("data.agreedAt", JsonFieldType.STRING, "동의 시각(ISO-8601 UTC)"),
        errorNull());
  }

  private static List<FieldDescriptor> emailCodeRequestFields() {
    return List.of(field("email", JsonFieldType.STRING, "인증번호를 받을 이메일(형식 검증)"));
  }

  private static List<FieldDescriptor> emailCodeResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"),
        field("data.email", JsonFieldType.STRING, "마스킹된 이메일(예: mi***@example.com)"),
        field("data.expiresIn", JsonFieldType.NUMBER, "인증번호 만료까지 초"),
        errorNull());
  }

  private static List<FieldDescriptor> emailVerifyRequestFields() {
    return List.of(
        field("email", JsonFieldType.STRING, "인증번호를 발송한 이메일과 일치"),
        field("code", JsonFieldType.STRING, "발송된 인증번호(빈값 불가)"));
  }

  private static List<FieldDescriptor> emailVerifyResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"),
        field("data.email", JsonFieldType.STRING, "마스킹된 이메일"),
        field("data.verified", JsonFieldType.BOOLEAN, "검증 완료 여부(true)"),
        errorNull());
  }

  private static List<FieldDescriptor> onboardingRequestFields() {
    return List.of(
        field("firstName", JsonFieldType.STRING, "이름(필수)"),
        field("lastName", JsonFieldType.STRING, "성(필수)"),
        field("gender", JsonFieldType.STRING, "성별: MALE | FEMALE(필수)"),
        field("birthDate", JsonFieldType.STRING, "생년월일 YYYY-MM-DD, 과거만(필수)"),
        field("country", JsonFieldType.STRING, "국적 ISO 3166-1 alpha-2 코드 예: KR(필수)"),
        field(
            "occupation",
            JsonFieldType.STRING,
            "직업 enum(필수): UNDERGRADUATE_STUDENT|GRADUATE_STUDENT|EXCHANGE_STUDENT|EDUCATION_ACADEMIC_RESEARCH|IT_SOFTWARE_ENGINEERING|DEVELOPER|DESIGNER"),
        field("email", JsonFieldType.STRING, "사전 인증된 이메일과 일치(필수)"),
        field(
            "visaType",
            JsonFieldType.STRING,
            "비자유형 enum(필수): DIPLOMATIC_OFFICIAL_A-1_A-2|VISA_EXEMPTED_B|JOURNALISM_RELIGIOUS_AFFAIRS_C-1_D-5_D-6|SHORT_TERM_VISIT_C-2_C-3|STUDY_D-2|TRAINEE_D-3_D-4|INTRA_COMPANY_TRANSFER_D-7|PROFESSIONAL_C-4_D-1_D-8_D-9_D-10_E-1_E-2_E-3_E-4_E-5_E-6_E-7|NON_PROFESSIONAL_E-8_E-9_E-10|WORKING_HOLIDAY_H-1|WORK_AND_VISIT_H-2|FAMILY_VISITOR_DEPENDENT_F-1_F-2_F-3|OVERSEAS_KOREAN_F-4|PERMANENT_RESIDENCE_F-5|MARRIAGE_MIGRANT_F-6|OTHERS_G-1"));
  }

  private static List<FieldDescriptor> refreshTokenRequestField(String description) {
    return List.of(field("refreshToken", JsonFieldType.STRING, description));
  }

  private static List<FieldDescriptor> patchRequestFields() {
    return List.of(
        optField("firstName", JsonFieldType.STRING, "이름(세입자·선택)"),
        optField("lastName", JsonFieldType.STRING, "성(세입자·선택)"),
        optField("gender", JsonFieldType.STRING, "성별 MALE|FEMALE(세입자·선택)"),
        optField("birthDate", JsonFieldType.STRING, "생년월일 YYYY-MM-DD, 과거만(세입자·선택)"),
        optField("country", JsonFieldType.STRING, "국적 ISO 코드(세입자·선택)"),
        optField("occupation", JsonFieldType.STRING, "직업 enum(세입자·선택)"),
        optField("visaType", JsonFieldType.STRING, "비자유형 enum(세입자·선택)"),
        optField("name", JsonFieldType.STRING, "이름(임대인 전체 이름·선택)"),
        optField(
            "phoneNumber",
            JsonFieldType.STRING,
            "연락처(임대인·선택) — 새 번호는 SMS 재인증(§4-1·§4-2) 후에만 반영(미인증 422)"),
        optField("marketingAgreed", JsonFieldType.BOOLEAN, "마케팅 수신 동의(선택)"));
  }

  private static List<FieldDescriptor> tokenResponseFields(String accessDesc, String refreshDesc) {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"),
        field("data.tokenType", JsonFieldType.STRING, "토큰 타입(Bearer)"),
        field("data.accessToken", JsonFieldType.STRING, accessDesc),
        field("data.refreshToken", JsonFieldType.STRING, refreshDesc),
        field("data.expiresIn", JsonFieldType.NUMBER, "access 토큰 만료까지 초(3600)"),
        errorNull());
  }

  private static List<FieldDescriptor> profileFields(String prefix) {
    return List.of(
        field(prefix + "id", JsonFieldType.NUMBER, "회원 ID"),
        field(prefix + "firstName", JsonFieldType.STRING, "이름"),
        field(prefix + "lastName", JsonFieldType.STRING, "성"),
        field(prefix + "nickname", JsonFieldType.STRING, "닉네임(서버 배정, 형용사+사물)"),
        field(prefix + "gender", JsonFieldType.STRING, "성별(MALE|FEMALE)"),
        field(prefix + "birthDate", JsonFieldType.STRING, "생년월일(YYYY-MM-DD)"),
        field(prefix + "country", JsonFieldType.STRING, "국적 ISO 코드"),
        field(prefix + "countryName", JsonFieldType.STRING, "국가 표시명(countries resolve)"),
        field(prefix + "countryFlag", JsonFieldType.STRING, "국기 이미지 URL(countries resolve)"),
        field(prefix + "occupation", JsonFieldType.STRING, "직업 enum"),
        field(prefix + "email", JsonFieldType.STRING, "인증된 연락 이메일"),
        field(prefix + "visaType", JsonFieldType.STRING, "비자유형 enum"));
  }

  private static List<FieldDescriptor> profileResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"),
        field("data.id", JsonFieldType.NUMBER, "회원 ID"),
        field("data.userType", JsonFieldType.STRING, "회원 역할(TENANT|LANDLORD) — 응답 필드가 이에 따라 갈린다"),
        field("data.firstName", JsonFieldType.STRING, "이름(세입자)"),
        field("data.lastName", JsonFieldType.STRING, "성(세입자)"),
        optField("data.name", JsonFieldType.STRING, "전체 이름(임대인만 — 세입자는 생략)"),
        field("data.nickname", JsonFieldType.STRING, "닉네임(서버 배정, 형용사+사물)"),
        field("data.gender", JsonFieldType.STRING, "성별(MALE|FEMALE)"),
        field("data.birthDate", JsonFieldType.STRING, "생년월일(YYYY-MM-DD)"),
        field("data.country", JsonFieldType.STRING, "국적 ISO 코드"),
        field("data.countryName", JsonFieldType.STRING, "국가 표시명(countries resolve)"),
        field("data.countryFlag", JsonFieldType.STRING, "국기 이미지 URL(countries resolve)"),
        field("data.occupation", JsonFieldType.STRING, "직업 enum"),
        field("data.email", JsonFieldType.STRING, "인증된 연락 이메일"),
        field("data.visaType", JsonFieldType.STRING, "비자유형 enum"),
        optField("data.phoneNumber", JsonFieldType.STRING, "연락처(임대인만·본인 조회 평문 — 세입자는 생략)"),
        field("data.status", JsonFieldType.STRING, "회원 상태(PENDING|TERMS_AGREED|ACTIVE|WITHDRAWN)"),
        field("data.termsOfServiceAgreed", JsonFieldType.BOOLEAN, "이용약관 동의 여부"),
        field("data.privacyPolicyAgreed", JsonFieldType.BOOLEAN, "개인정보처리방침 동의 여부"),
        field("data.marketingAgreed", JsonFieldType.BOOLEAN, "마케팅 수신 동의 여부"),
        field("data.createdAt", JsonFieldType.STRING, "가입 시각(ISO-8601 UTC)"),
        errorNull());
  }

  private static List<FieldDescriptor> onboardingResponseFields() {
    java.util.ArrayList<FieldDescriptor> fields = new java.util.ArrayList<>();
    fields.add(field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"));
    fields.addAll(profileFields("data.user."));
    fields.add(field("data.user.userType", JsonFieldType.STRING, "회원 역할(TENANT|LANDLORD)"));
    fields.add(
        optField(
            "data.user.phoneNumber", JsonFieldType.STRING, "연락처 — 세입자는 미수집(null), 임대인은 인증된 연락처"));
    fields.add(field("data.user.status", JsonFieldType.STRING, "회원 상태(ACTIVE)"));
    fields.add(field("data.user.marketingAgreed", JsonFieldType.BOOLEAN, "마케팅 수신 동의 여부"));
    fields.add(field("data.user.createdAt", JsonFieldType.STRING, "가입 시각(ISO-8601 UTC)"));
    fields.add(field("data.tokenType", JsonFieldType.STRING, "토큰 타입(Bearer)"));
    fields.add(
        field(
            "data.accessToken",
            JsonFieldType.STRING,
            "정식 access 토큰(JWT, onboardingCompleted=true)"));
    fields.add(field("data.refreshToken", JsonFieldType.STRING, "정식 refresh 토큰(불투명)"));
    fields.add(field("data.expiresIn", JsonFieldType.NUMBER, "access 토큰 만료까지 초(3600)"));
    fields.add(errorNull());
    return fields;
  }

  private String socialLogin(String subject) throws Exception {
    return mockMvc
        .perform(
            post("/api/v1/auth/social-login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\":\"GOOGLE\",\"idToken\":\"" + subject + "\"}"))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private void agreeTerms(String token) throws Exception {
    mockMvc
        .perform(
            post("/api/v1/auth/terms")
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(termsJson(true, true, false)))
        .andExpect(status().isOk());
  }

  private void sendAndVerifyEmail(String token, String email) throws Exception {
    mockMvc
        .perform(
            post("/api/v1/auth/email/verification-code")
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\"}"))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            post("/api/v1/auth/email/verify")
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"code\":\"" + sentCodes.get(email) + "\"}"))
        .andExpect(status().isOk());
  }

  /** 신규 소셜 로그인 → 약관 동의 → 이메일 인증 → 온보딩 완료까지 수행하고 정식 access 토큰을 돌려준다. */
  private String onboardCompletely(String subject) throws Exception {
    String onboardingToken = read(socialLogin(subject), "data", "accessToken");
    agreeTerms(onboardingToken);
    sendAndVerifyEmail(onboardingToken, emailFor(subject));
    String body =
        mockMvc
            .perform(
                post("/api/v1/auth/onboarding")
                    .header(HttpHeaders.AUTHORIZATION, bearer(onboardingToken))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(onboardingJson(emailFor(subject))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return read(body, "data", "accessToken");
  }

  private String expiredAccessToken() {
    SecretKey key = Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
    Instant now = Instant.now();
    return Jwts.builder()
        .issuer(jwtProperties.getIssuer())
        .subject("1")
        .claim("onboardingCompleted", true)
        .issuedAt(Date.from(now.minusSeconds(7200)))
        .expiration(Date.from(now.minusSeconds(3600)))
        .signWith(key)
        .compact();
  }

  private String read(String json, String... path) throws Exception {
    JsonNode node = objectMapper.readTree(json);
    for (String key : path) {
      node = node.path(key);
    }
    return node.asText();
  }

  private static String bearer(String token) {
    return "Bearer " + token;
  }

  private static String emailFor(String subject) {
    return subject + "@mail.example";
  }

  private static String termsJson(boolean terms, boolean privacy, boolean marketing) {
    return "{\"termsOfServiceAgreed\":"
        + terms
        + ",\"privacyPolicyAgreed\":"
        + privacy
        + ",\"marketingAgreed\":"
        + marketing
        + "}";
  }

  private static String onboardingJson(String email) {
    return """
        {
          "firstName": "Gil",
          "lastName": "Hong",
          "gender": "MALE",
          "birthDate": "1990-01-01",
          "country": "KR",
          "occupation": "UNDERGRADUATE_STUDENT",
          "email": "%s",
          "visaType": "SHORT_TERM_VISIT_C-2_C-3"
        }
        """
        .formatted(email);
  }

  private static String onboardingBlankFirstName() {
    return """
        {
          "firstName": "",
          "lastName": "Hong",
          "gender": "MALE",
          "birthDate": "1990-01-01",
          "country": "KR",
          "occupation": "UNDERGRADUATE_STUDENT",
          "email": "gil@mail.example",
          "visaType": "SHORT_TERM_VISIT_C-2_C-3"
        }
        """;
  }
}
