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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.epages.restdocs.apispec.ResourceSnippetParameters;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kohere.TestcontainersConfiguration;
import com.kohere.auth.application.PhoneVerificationProperties;
import com.kohere.auth.domain.AppleAuthClient;
import com.kohere.auth.domain.BusinessRegistryVerifier;
import com.kohere.auth.domain.BusinessVerificationUpstreamException;
import com.kohere.auth.domain.InvalidSocialTokenException;
import com.kohere.auth.domain.OidcTokenVerifier;
import com.kohere.auth.domain.OidcUser;
import com.kohere.auth.domain.SmsDispatchException;
import com.kohere.auth.domain.VerificationEmailSender;
import com.kohere.auth.domain.VerificationSmsSender;
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
 * 임대인 트랙(ADR-0034) Spring REST Docs 스니펫 생성 테스트 — 연락처 SMS 인증(발송·확인)·임대인 온보딩의 성공 응답과, 온보딩과 분리된
 * 사업자등록번호 검증(온보딩 완료 임대인 전용, 무상태)의 성공·에러 응답을 스펙(01-auth-onboarding.md §4-1·§4-2·§5-1·§5-2)대로 {@code
 * build/generated-snippets}에 생성한다. 소셜 OIDC는 가짜 주입, SMS 발송({@link VerificationSmsSender})·사업자번호 외부
 * 검증({@link BusinessRegistryVerifier})은 모킹하고 Security·JPA·Redis·JWT는 실제 구동한다.
 *
 * <p>임대인 흐름: 소셜로그인(PENDING) → 약관 동의(TERMS_AGREED) → 연락처 인증(코드 발송·확인) → 임대인
 * 온보딩(ACTIVE·userType=LANDLORD). 사업자등록번호 검증은 온보딩에서 분리되어, 온보딩을 마친(ACTIVE) 임대인이 정식 토큰(ROLE_USER)으로 별도
 * 호출한다(§5-1). SMS 발송 모킹으로 인증번호를 캡처한다.
 */
@SpringBootTest
@ExtendWith(RestDocumentationExtension.class)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class LandlordOnboardingDocsTest {

  private static final String INVALID_SOCIAL_TOKEN = "invalid-social-token";
  private static final String MALFORMED_BODY = "{ \"oops\" }";
  private static final String PHONE = "01012345678";
  private static final String BIRTH_DATE = "1988-05-20";
  private static final String BIZ_NUMBER = "1234567890";

  // 서명이 깨진(다른 키) 액세스 토큰 — 401 UNAUTHENTICATED 를 유발하면서도 구조상 JWT 라 restdocs-api-spec 이
  // 무인증 예시에서도 bearerAuthJWT 보안 스킴을 도출하게 한다(AuthOnboardingDocsTest 와 동일 의도).
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
  @Autowired private PhoneVerificationProperties phoneProperties;
  @MockitoBean private VerificationSmsSender smsSender;
  @MockitoBean private BusinessRegistryVerifier businessVerifier;
  @MockitoBean private VerificationEmailSender emailSender; // 임대인 흐름 미사용(컨텍스트 충족용)
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
    // 발송 인증번호를 연락처별로 기록(검증 단계에서 사용). 실제 SMS 발송은 하지 않는다.
    doAnswer(
            inv -> {
              sentCodes.put(inv.getArgument(0), inv.getArgument(1));
              return null;
            })
        .when(smsSender)
        .send(any(), any());
    // 정상(계속) 사업자 — 기본 검증 통과(특정 실패·장애 케이스만 개별 오버라이드)
    when(businessVerifier.verify(BIZ_NUMBER)).thenReturn(true);
  }

  @Test
  void generatesLandlordOnboardingSnippets() throws Exception {
    String onboardingToken = read(socialLogin("docs-landlord-1"), "data", "accessToken");
    agreeTerms(onboardingToken);

    // 연락처 인증번호 발송
    mockMvc
        .perform(
            post("/api/v1/auth/phone/verification-code")
                .header(HttpHeaders.AUTHORIZATION, bearer(onboardingToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phoneNumber\":\"" + PHONE + "\"}"))
        .andExpect(status().isOk())
        .andDo(
            document(
                "auth-phone-verification-code",
                resourceDetails()
                    .summary("연락처 인증번호 발송(임대인) — 동기 발송 성공 후 챌린지 저장(phoneNumber 마스킹 반환)"),
                requestFields(phoneCodeRequestFields()),
                responseFields(phoneCodeResponseFields())));

    // 연락처 인증번호 확인
    mockMvc
        .perform(
            post("/api/v1/auth/phone/verify")
                .header(HttpHeaders.AUTHORIZATION, bearer(onboardingToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"phoneNumber\":\""
                        + PHONE
                        + "\",\"code\":\""
                        + sentCodes.get(PHONE)
                        + "\"}"))
        .andExpect(status().isOk())
        .andDo(
            document(
                "auth-phone-verify",
                resourceDetails().summary("연락처 인증번호 확인(임대인) — 검증 완료(VERIFIED) 마킹"),
                requestFields(phoneVerifyRequestFields()),
                responseFields(phoneVerifyResponseFields())));

    // 임대인 온보딩 제출 → 정식 토큰(사업자번호는 온보딩에서 수집하지 않음)
    String onboardingBody =
        mockMvc
            .perform(
                post("/api/v1/auth/landlord/onboarding")
                    .header(HttpHeaders.AUTHORIZATION, bearer(onboardingToken))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(landlordJson("Kim Imdae", PHONE)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.user.userType").value("LANDLORD"))
            .andDo(
                document(
                    "auth-landlord-onboarding",
                    resourceDetails()
                        .summary(
                            "임대인 온보딩 제출 — 약관·연락처 인증만으로 TERMS_AGREED→ACTIVE 전이, userType=LANDLORD 확정·정식 토큰 발급"),
                    requestFields(landlordOnboardingRequestFields()),
                    responseFields(landlordOnboardingResponseFields())))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String accessToken = read(onboardingBody, "data", "accessToken");

    // 사업자등록번호 검증 — 온보딩 완료(ACTIVE) 임대인이 정식 토큰으로 호출하는 무상태 검증
    mockMvc
        .perform(
            post("/api/v1/auth/business/verify")
                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"businessRegistrationNumber\":\"" + BIZ_NUMBER + "\"}"))
        .andExpect(status().isOk())
        .andDo(
            document(
                "auth-business-verify",
                resourceDetails()
                    .summary(
                        "사업자등록번호 검증(임대인 전용, 온보딩 후) — 정식 토큰(ACTIVE)으로 정상 사업자 무상태 검증(번호 마스킹 반환)"),
                requestFields(businessVerifyRequestFields()),
                responseFields(businessVerifyResponseFields())));

    // 임대인 내 프로필 조회 — 생년월일 포함(프로필 조회 §8, #131). 세입자 전용 필드·이메일 미포함, 연락처는 본인이라 평문.
    mockMvc
        .perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.userType").value("LANDLORD"))
        .andExpect(jsonPath("$.data.birthDate").value(BIRTH_DATE))
        .andDo(
            document(
                "users-me-landlord",
                resourceDetails()
                    .summary("내 프로필 조회(임대인) — name·생년월일·연락처(평문) 중심, 세입자 전용 필드·이메일 미포함"),
                responseFields(landlordProfileResponseFields())));
  }

  /** 스펙의 "발생 가능한 에러"를 엔드포인트별로 실제 트리거해 스니펫으로 생성하고 status·error.code를 단정한다. */
  @Test
  void generatesLandlordOnboardingErrorSnippets() throws Exception {
    String pendingToken = read(socialLogin("err-l-pending"), "data", "accessToken"); // PENDING
    String termsToken = read(socialLogin("err-l-terms"), "data", "accessToken");
    agreeTerms(termsToken); // TERMS_AGREED(연락처 미검증, 온보딩 토큰)
    String activeAccess = onboardLandlordCompletely("err-l-active"); // 정식 ACTIVE 임대인 access

    String expiredToken = expiredAccessToken();

    // ===== phone/verification-code =====
    perform(
        post("/api/v1/auth/phone/verification-code")
            .header(HttpHeaders.AUTHORIZATION, bearer(termsToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"phoneNumber\":\"\"}"),
        status().isBadRequest(),
        "INVALID_INPUT",
        "auth-phone-verification-code-invalid-input",
        "연락처 인증번호 발송 — 입력 검증 실패 (400 INVALID_INPUT): phoneNumber 누락/빈값");

    perform(
        post("/api/v1/auth/phone/verification-code")
            .header(HttpHeaders.AUTHORIZATION, bearer(termsToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content(MALFORMED_BODY),
        status().isBadRequest(),
        "MALFORMED_REQUEST",
        "auth-phone-verification-code-malformed",
        "연락처 인증번호 발송 — 본문 해석 불가 (400 MALFORMED_REQUEST)");

    perform(
        post("/api/v1/auth/phone/verification-code")
            .header(HttpHeaders.AUTHORIZATION, bearer(FORGED_TOKEN))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"phoneNumber\":\"" + PHONE + "\"}"),
        status().isUnauthorized(),
        "UNAUTHENTICATED",
        "auth-phone-verification-code-unauthenticated",
        "연락처 인증번호 발송 — 인증 누락/위조 (401 UNAUTHENTICATED)");

    perform(
        post("/api/v1/auth/phone/verification-code")
            .header(HttpHeaders.AUTHORIZATION, bearer(expiredToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"phoneNumber\":\"" + PHONE + "\"}"),
        status().isUnauthorized(),
        "TOKEN_EXPIRED",
        "auth-phone-verification-code-token-expired",
        "연락처 인증번호 발송 — 액세스 토큰 만료 (401 TOKEN_EXPIRED)");

    // 약관 미동의(PENDING) 상태 발송 → 약관 동의 선행 안내 422
    perform(
        post("/api/v1/auth/phone/verification-code")
            .header(HttpHeaders.AUTHORIZATION, bearer(pendingToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"phoneNumber\":\"" + PHONE + "\"}"),
        status().isUnprocessableEntity(),
        "AUTH_TERMS_AGREEMENT_REQUIRED",
        "auth-phone-verification-code-terms-required",
        "연락처 인증번호 발송 — 약관 미동의(PENDING) 상태 (422 AUTH_TERMS_AGREEMENT_REQUIRED)");

    // 재발송 간격 미달 → 429 (첫 발송 성공 직후 즉시 재요청)
    String resendToken = read(socialLogin("err-l-resend"), "data", "accessToken");
    agreeTerms(resendToken);
    mockMvc
        .perform(
            post("/api/v1/auth/phone/verification-code")
                .header(HttpHeaders.AUTHORIZATION, bearer(resendToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phoneNumber\":\"" + PHONE + "\"}"))
        .andExpect(status().isOk());
    perform(
        post("/api/v1/auth/phone/verification-code")
            .header(HttpHeaders.AUTHORIZATION, bearer(resendToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"phoneNumber\":\"" + PHONE + "\"}"),
        status().isTooManyRequests(),
        "TOO_MANY_REQUESTS",
        "auth-phone-verification-code-rate-limited",
        "연락처 인증번호 발송 — 재발송 간격 미달 (429 TOO_MANY_REQUESTS)");

    // SMS 발송 실패(provider 장애·타임아웃) → 502, 챌린지 미저장
    String smsFailToken = read(socialLogin("err-l-sms"), "data", "accessToken");
    agreeTerms(smsFailToken);
    String smsFailPhone = "01099990000";
    doThrow(new SmsDispatchException(new RuntimeException("solapi down")))
        .when(smsSender)
        .send(eq(smsFailPhone), any());
    perform(
        post("/api/v1/auth/phone/verification-code")
            .header(HttpHeaders.AUTHORIZATION, bearer(smsFailToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"phoneNumber\":\"" + smsFailPhone + "\"}"),
        status().isBadGateway(),
        "UPSTREAM_ERROR",
        "auth-phone-verification-code-dispatch-failed",
        "연락처 인증번호 발송 — SMS 발송 실패 (502 UPSTREAM_ERROR): 챌린지 미저장");

    // US-1-5: 정식(ACTIVE) 임대인도 프로필 연락처 변경을 위해 인증번호를 받을 수 있다(온보딩 전용 아님 — 409 아님, ADR-0034 §6·§8).
    mockMvc
        .perform(
            post("/api/v1/auth/phone/verification-code")
                .header(HttpHeaders.AUTHORIZATION, bearer(activeAccess))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phoneNumber\":\"" + PHONE + "\"}"))
        .andExpect(status().isOk());

    // ===== phone/verify =====
    perform(
        post("/api/v1/auth/phone/verify")
            .header(HttpHeaders.AUTHORIZATION, bearer(pendingToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"phoneNumber\":\"" + PHONE + "\",\"code\":\"000000\"}"),
        status().isUnprocessableEntity(),
        "AUTH_PHONE_VERIFICATION_FAILED",
        "auth-phone-verify-failed",
        "연락처 인증 — 챌린지 부재/만료/불일치 (422 AUTH_PHONE_VERIFICATION_FAILED)");

    perform(
        post("/api/v1/auth/phone/verify")
            .header(HttpHeaders.AUTHORIZATION, bearer(pendingToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"phoneNumber\":\"\",\"code\":\"123456\"}"),
        status().isBadRequest(),
        "INVALID_INPUT",
        "auth-phone-verify-invalid-input",
        "연락처 인증 확인 — 입력 검증 실패 (400 INVALID_INPUT): phoneNumber/code 누락·빈값");

    perform(
        post("/api/v1/auth/phone/verify")
            .header(HttpHeaders.AUTHORIZATION, bearer(pendingToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content(MALFORMED_BODY),
        status().isBadRequest(),
        "MALFORMED_REQUEST",
        "auth-phone-verify-malformed",
        "연락처 인증 확인 — 본문 해석 불가 (400 MALFORMED_REQUEST)");

    perform(
        post("/api/v1/auth/phone/verify")
            .header(HttpHeaders.AUTHORIZATION, bearer(FORGED_TOKEN))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"phoneNumber\":\"" + PHONE + "\",\"code\":\"000000\"}"),
        status().isUnauthorized(),
        "UNAUTHENTICATED",
        "auth-phone-verify-unauthenticated",
        "연락처 인증 확인 — 인증 누락/위조 (401 UNAUTHENTICATED)");

    perform(
        post("/api/v1/auth/phone/verify")
            .header(HttpHeaders.AUTHORIZATION, bearer(expiredToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"phoneNumber\":\"" + PHONE + "\",\"code\":\"000000\"}"),
        status().isUnauthorized(),
        "TOKEN_EXPIRED",
        "auth-phone-verify-token-expired",
        "연락처 인증 확인 — 액세스 토큰 만료 (401 TOKEN_EXPIRED)");

    // 코드 불일치 누적 → 시도 상한 초과 429
    String attemptsToken = read(socialLogin("err-l-attempts"), "data", "accessToken");
    agreeTerms(attemptsToken);
    mockMvc
        .perform(
            post("/api/v1/auth/phone/verification-code")
                .header(HttpHeaders.AUTHORIZATION, bearer(attemptsToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phoneNumber\":\"" + PHONE + "\"}"))
        .andExpect(status().isOk());
    for (int i = 0; i < phoneProperties.getMaxAttempts() - 1; i++) {
      mockMvc
          .perform(
              post("/api/v1/auth/phone/verify")
                  .header(HttpHeaders.AUTHORIZATION, bearer(attemptsToken))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"phoneNumber\":\"" + PHONE + "\",\"code\":\"000000\"}"))
          .andExpect(status().isUnprocessableEntity());
    }
    perform(
        post("/api/v1/auth/phone/verify")
            .header(HttpHeaders.AUTHORIZATION, bearer(attemptsToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"phoneNumber\":\"" + PHONE + "\",\"code\":\"000000\"}"),
        status().isTooManyRequests(),
        "TOO_MANY_REQUESTS",
        "auth-phone-verify-rate-limited",
        "연락처 인증 확인 — 코드 불일치 누적·시도 상한 초과 (429 TOO_MANY_REQUESTS)");

    // ===== business/verify (온보딩 후 ACTIVE 임대인 전용) =====
    // 형식 위반 — 정식 토큰(ACTIVE 임대인) 인가 통과 후 입력 검증 실패
    perform(
        post("/api/v1/auth/business/verify")
            .header(HttpHeaders.AUTHORIZATION, bearer(activeAccess))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"businessRegistrationNumber\":\"123\"}"),
        status().isBadRequest(),
        "INVALID_INPUT",
        "auth-business-verify-invalid-input",
        "사업자번호 검증 — 입력 검증 실패 (400 INVALID_INPUT): 숫자 10자리 형식 위반");

    perform(
        post("/api/v1/auth/business/verify")
            .header(HttpHeaders.AUTHORIZATION, bearer(activeAccess))
            .contentType(MediaType.APPLICATION_JSON)
            .content(MALFORMED_BODY),
        status().isBadRequest(),
        "MALFORMED_REQUEST",
        "auth-business-verify-malformed",
        "사업자번호 검증 — 본문 해석 불가 (400 MALFORMED_REQUEST)");

    perform(
        post("/api/v1/auth/business/verify")
            .header(HttpHeaders.AUTHORIZATION, bearer(FORGED_TOKEN))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"businessRegistrationNumber\":\"" + BIZ_NUMBER + "\"}"),
        status().isUnauthorized(),
        "UNAUTHENTICATED",
        "auth-business-verify-unauthenticated",
        "사업자번호 검증 — 인증 누락/위조 (401 UNAUTHENTICATED)");

    perform(
        post("/api/v1/auth/business/verify")
            .header(HttpHeaders.AUTHORIZATION, bearer(expiredToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"businessRegistrationNumber\":\"" + BIZ_NUMBER + "\"}"),
        status().isUnauthorized(),
        "TOKEN_EXPIRED",
        "auth-business-verify-token-expired",
        "사업자번호 검증 — 액세스 토큰 만료 (401 TOKEN_EXPIRED)");

    // 온보딩 미완료(온보딩 토큰, ROLE_ONBOARDING) 호출 → 정식 토큰 필요 403
    perform(
        post("/api/v1/auth/business/verify")
            .header(HttpHeaders.AUTHORIZATION, bearer(termsToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"businessRegistrationNumber\":\"" + BIZ_NUMBER + "\"}"),
        status().isForbidden(),
        "AUTH_ONBOARDING_REQUIRED",
        "auth-business-verify-onboarding-required",
        "사업자번호 검증 — 온보딩 미완료(온보딩 토큰) 호출 (403 AUTH_ONBOARDING_REQUIRED): 온보딩 완료 후 호출");

    // 미등록·휴폐업·진위 실패 → 422 (외부 검증 결과 false)
    String failNumber = "9999999999";
    when(businessVerifier.verify(failNumber)).thenReturn(false);
    perform(
        post("/api/v1/auth/business/verify")
            .header(HttpHeaders.AUTHORIZATION, bearer(activeAccess))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"businessRegistrationNumber\":\"" + failNumber + "\"}"),
        status().isUnprocessableEntity(),
        "AUTH_BUSINESS_NUMBER_VERIFICATION_FAILED",
        "auth-business-verify-failed",
        "사업자번호 검증 — 미등록·휴폐업·진위 실패 (422 AUTH_BUSINESS_NUMBER_VERIFICATION_FAILED)");

    // 외부 검증 API 장애·타임아웃 → 502
    String upstreamNumber = "8888888888";
    when(businessVerifier.verify(upstreamNumber))
        .thenThrow(new BusinessVerificationUpstreamException(new RuntimeException("bizno down")));
    perform(
        post("/api/v1/auth/business/verify")
            .header(HttpHeaders.AUTHORIZATION, bearer(activeAccess))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"businessRegistrationNumber\":\"" + upstreamNumber + "\"}"),
        status().isBadGateway(),
        "UPSTREAM_ERROR",
        "auth-business-verify-upstream-error",
        "사업자번호 검증 — 외부 검증 API 장애·타임아웃 (502 UPSTREAM_ERROR): 검증 결과 미저장");

    // ===== landlord/onboarding =====
    perform(
        post("/api/v1/auth/landlord/onboarding")
            .header(HttpHeaders.AUTHORIZATION, bearer(pendingToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content(landlordJson("", PHONE)),
        status().isBadRequest(),
        "INVALID_INPUT",
        "auth-landlord-onboarding-invalid-input",
        "임대인 온보딩 — 입력 검증 실패 (400 INVALID_INPUT): name/phoneNumber 누락·형식 위반");

    perform(
        post("/api/v1/auth/landlord/onboarding")
            .header(HttpHeaders.AUTHORIZATION, bearer(pendingToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content(MALFORMED_BODY),
        status().isBadRequest(),
        "MALFORMED_REQUEST",
        "auth-landlord-onboarding-malformed",
        "임대인 온보딩 — 본문 해석 불가 (400 MALFORMED_REQUEST)");

    perform(
        post("/api/v1/auth/landlord/onboarding")
            .header(HttpHeaders.AUTHORIZATION, bearer(FORGED_TOKEN))
            .contentType(MediaType.APPLICATION_JSON)
            .content(landlordJson("Kim Imdae", PHONE)),
        status().isUnauthorized(),
        "UNAUTHENTICATED",
        "auth-landlord-onboarding-unauthenticated",
        "임대인 온보딩 — 인증 누락/위조 (401 UNAUTHENTICATED)");

    perform(
        post("/api/v1/auth/landlord/onboarding")
            .header(HttpHeaders.AUTHORIZATION, bearer(expiredToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content(landlordJson("Kim Imdae", PHONE)),
        status().isUnauthorized(),
        "TOKEN_EXPIRED",
        "auth-landlord-onboarding-token-expired",
        "임대인 온보딩 — 액세스 토큰 만료 (401 TOKEN_EXPIRED)");

    // 약관 미동의(PENDING) 상태 제출 → 약관 동의 선행 안내 422(연락처 검사보다 먼저)
    perform(
        post("/api/v1/auth/landlord/onboarding")
            .header(HttpHeaders.AUTHORIZATION, bearer(pendingToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content(landlordJson("Kim Imdae", PHONE)),
        status().isUnprocessableEntity(),
        "AUTH_TERMS_AGREEMENT_REQUIRED",
        "auth-landlord-onboarding-terms-required",
        "임대인 온보딩 — 약관 미동의 상태 (422 AUTH_TERMS_AGREEMENT_REQUIRED)");

    // 약관 동의했으나 연락처 미인증 → 422 AUTH_PHONE_NOT_VERIFIED
    String phoneNeedToken = read(socialLogin("err-l-phoneneed"), "data", "accessToken");
    agreeTerms(phoneNeedToken);
    perform(
        post("/api/v1/auth/landlord/onboarding")
            .header(HttpHeaders.AUTHORIZATION, bearer(phoneNeedToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content(landlordJson("Kim Imdae", PHONE)),
        status().isUnprocessableEntity(),
        "AUTH_PHONE_NOT_VERIFIED",
        "auth-landlord-onboarding-phone-not-verified",
        "임대인 온보딩 — 연락처 미인증·불일치 (422 AUTH_PHONE_NOT_VERIFIED)");

    perform(
        post("/api/v1/auth/landlord/onboarding")
            .header(HttpHeaders.AUTHORIZATION, bearer(activeAccess))
            .contentType(MediaType.APPLICATION_JSON)
            .content(landlordJson("Kim Imdae", PHONE)),
        status().isConflict(),
        "AUTH_ONBOARDING_ALREADY_COMPLETED",
        "auth-landlord-onboarding-already-completed",
        "임대인 온보딩 — 이미 완료한 사용자의 재요청 (409 AUTH_ONBOARDING_ALREADY_COMPLETED)");
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

  private static List<FieldDescriptor> phoneCodeRequestFields() {
    return List.of(field("phoneNumber", JsonFieldType.STRING, "인증번호를 받을 휴대폰 번호(빈값 불가)"));
  }

  private static List<FieldDescriptor> phoneCodeResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"),
        field("data.phoneNumber", JsonFieldType.STRING, "마스킹된 연락처(예: 010-****-5678)"),
        field("data.expiresIn", JsonFieldType.NUMBER, "인증번호 만료까지 초"),
        errorNull());
  }

  private static List<FieldDescriptor> phoneVerifyRequestFields() {
    return List.of(
        field("phoneNumber", JsonFieldType.STRING, "인증번호를 발송한 연락처와 일치"),
        field("code", JsonFieldType.STRING, "발송된 인증번호(빈값 불가)"));
  }

  private static List<FieldDescriptor> phoneVerifyResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"),
        field("data.phoneNumber", JsonFieldType.STRING, "마스킹된 연락처"),
        field("data.verified", JsonFieldType.BOOLEAN, "검증 완료 여부(true)"),
        errorNull());
  }

  private static List<FieldDescriptor> businessVerifyRequestFields() {
    return List.of(
        field("businessRegistrationNumber", JsonFieldType.STRING, "사업자등록번호 숫자 10자리(형식 검증)"));
  }

  private static List<FieldDescriptor> businessVerifyResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"),
        field(
            "data.businessRegistrationNumber", JsonFieldType.STRING, "마스킹된 사업자등록번호(예: ****567890)"),
        field("data.verified", JsonFieldType.BOOLEAN, "정상 사업자 검증 완료 여부(true)"),
        errorNull());
  }

  private static List<FieldDescriptor> landlordOnboardingRequestFields() {
    return List.of(
        field("name", JsonFieldType.STRING, "임대인 이름(성·이름 합친 단일 이름, 필수·빈값 불가)"),
        field("phoneNumber", JsonFieldType.STRING, "사전 SMS 인증된 연락처와 일치(필수)"),
        field("birthDate", JsonFieldType.STRING, "생년월일(YYYY-MM-DD, 필수·과거 날짜만)"));
  }

  /**
   * 임대인 온보딩 응답 필드 — 세입자 전용 필드(firstName·lastName·gender·occupation·email·visaType)는 {@code null}이라
   * 응답에서 생략된다(UserProfileView {@code @JsonInclude(NON_NULL)}). 국적({@code country})·표시 언어({@code
   * lang})는 서버가 KR·ko로 고정 부여하므로 응답에 포함된다(ADR-0034 개정·#141). 임대인은 단일 {@code name}·생년월일({@code
   * birthDate})·마스킹된 {@code phoneNumber}를 받는다(spec §5-2, #131).
   */
  private static List<FieldDescriptor> landlordOnboardingResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"),
        field("data.user.id", JsonFieldType.NUMBER, "회원 ID"),
        field("data.user.name", JsonFieldType.STRING, "임대인 이름(요청 name — 성·이름 합친 전체 이름)"),
        field("data.user.nickname", JsonFieldType.STRING, "닉네임(서버 배정)"),
        field("data.user.birthDate", JsonFieldType.STRING, "생년월일(YYYY-MM-DD)"),
        field("data.user.country", JsonFieldType.STRING, "국적 ISO 코드(임대인은 서버 고정 KR)"),
        field("data.user.countryName", JsonFieldType.STRING, "국가 표시명(countries 참조 — South Korea)"),
        field("data.user.countryFlag", JsonFieldType.STRING, "국기 이미지 URL(flagcdn.com SVG)"),
        field("data.user.lang", JsonFieldType.STRING, "표시 언어 ISO 639-1(임대인은 서버 고정 ko)"),
        field("data.user.phoneNumber", JsonFieldType.STRING, "마스킹된 연락처(예: 010-****-5678)"),
        field("data.user.userType", JsonFieldType.STRING, "회원 역할(LANDLORD)"),
        field("data.user.status", JsonFieldType.STRING, "회원 상태(ACTIVE)"),
        field("data.user.marketingAgreed", JsonFieldType.BOOLEAN, "마케팅 수신 동의 여부"),
        field("data.user.createdAt", JsonFieldType.STRING, "가입 시각(ISO-8601 UTC)"),
        field("data.tokenType", JsonFieldType.STRING, "토큰 타입(Bearer)"),
        field(
            "data.accessToken",
            JsonFieldType.STRING,
            "정식 access 토큰(JWT, onboardingCompleted=true)"),
        field("data.refreshToken", JsonFieldType.STRING, "정식 refresh 토큰(불투명)"),
        field("data.expiresIn", JsonFieldType.NUMBER, "access 토큰 만료까지 초(3600)"),
        errorNull());
  }

  /**
   * 임대인 프로필 조회(GET /users/me) 응답 필드 — 세입자 전용 필드(firstName·lastName·gender·occupation·email·
   * visaType)는 {@code null}이라 생략된다(UserProfileResponse {@code @JsonInclude(NON_NULL)}). 국적({@code
   * country})·표시 언어({@code lang})는 서버가 KR·ko로 고정 부여하므로 포함된다(ADR-0034 개정·#141). 본인 조회이므로 {@code
   * phoneNumber}는 평문이다(spec §8, #131).
   */
  private static List<FieldDescriptor> landlordProfileResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"),
        field("data.id", JsonFieldType.NUMBER, "회원 ID"),
        field("data.userType", JsonFieldType.STRING, "회원 역할(LANDLORD)"),
        field("data.name", JsonFieldType.STRING, "임대인 이름(성·이름 합친 전체 이름)"),
        field("data.nickname", JsonFieldType.STRING, "닉네임(서버 배정)"),
        field("data.birthDate", JsonFieldType.STRING, "생년월일(YYYY-MM-DD)"),
        field("data.country", JsonFieldType.STRING, "국적 ISO 코드(임대인은 서버 고정 KR)"),
        field("data.countryName", JsonFieldType.STRING, "국가 표시명(South Korea)"),
        field("data.countryFlag", JsonFieldType.STRING, "국기 이미지 URL(flagcdn.com SVG)"),
        field("data.lang", JsonFieldType.STRING, "표시 언어 ISO 639-1(임대인은 서버 고정 ko)"),
        field("data.phoneNumber", JsonFieldType.STRING, "연락처(본인 조회는 평문)"),
        field("data.status", JsonFieldType.STRING, "회원 상태(ACTIVE)"),
        field("data.termsOfServiceAgreed", JsonFieldType.BOOLEAN, "이용약관 동의 여부"),
        field("data.privacyPolicyAgreed", JsonFieldType.BOOLEAN, "개인정보처리방침 동의 여부"),
        field("data.marketingAgreed", JsonFieldType.BOOLEAN, "마케팅 수신 동의 여부"),
        field("data.createdAt", JsonFieldType.STRING, "가입 시각(ISO-8601 UTC)"),
        errorNull());
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
                .content(
                    "{\"termsOfServiceAgreed\":true,\"privacyPolicyAgreed\":true,\"marketingAgreed\":false}"))
        .andExpect(status().isOk());
  }

  private void sendAndVerifyPhone(String token, String phone) throws Exception {
    mockMvc
        .perform(
            post("/api/v1/auth/phone/verification-code")
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phoneNumber\":\"" + phone + "\"}"))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            post("/api/v1/auth/phone/verify")
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"phoneNumber\":\""
                        + phone
                        + "\",\"code\":\""
                        + sentCodes.get(phone)
                        + "\"}"))
        .andExpect(status().isOk());
  }

  /** 신규 소셜 로그인 → 약관 동의 → 연락처 인증 → 임대인 온보딩까지 수행하고 정식 access 토큰을 돌려준다(사업자번호는 온보딩과 무관). */
  private String onboardLandlordCompletely(String subject) throws Exception {
    String token = read(socialLogin(subject), "data", "accessToken");
    agreeTerms(token);
    sendAndVerifyPhone(token, PHONE);
    String body =
        mockMvc
            .perform(
                post("/api/v1/auth/landlord/onboarding")
                    .header(HttpHeaders.AUTHORIZATION, bearer(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(landlordJson("Kim Imdae", PHONE)))
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

  private static String landlordJson(String name, String phone) {
    return "{\"name\":\""
        + name
        + "\",\"phoneNumber\":\""
        + phone
        + "\",\"birthDate\":\""
        + BIRTH_DATE
        + "\"}";
  }
}
