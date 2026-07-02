package com.kohere.gamification.infrastructure;

import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.document;
import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.resourceDetails;
import static com.epages.restdocs.apispec.ResourceDocumentation.resource;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.post;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.requestFields;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;
import static org.springframework.restdocs.request.RequestDocumentation.pathParameters;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.epages.restdocs.apispec.ResourceSnippetParameters;
import com.kohere.TestcontainersConfiguration;
import com.kohere.common.security.JwtProperties;
import com.kohere.common.security.JwtTokenService;
import com.kohere.gamification.infrastructure.QuizDocument.ChoiceSpec;
import com.kohere.user.api.UserAccountService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Spring REST Docs 스니펫 생성 테스트(ADR-0007·0016). 학습 퀴즈 엔드포인트(랜덤 조회·정답 채점)의 성공 응답과 스펙
 * (06-gamification.md)에 정의된 에러 응답을 {@code build/generated-snippets}에 생성해 OpenAPI3(Swagger UI)에
 * 합류한다.
 *
 * <p>cross-module 협력(user 표시 언어·userType)은 {@code @MockitoBean}으로 대체하고 access 토큰은 {@link
 * JwtTokenService}로 직접 발급한다. MongoDB(퀴즈 카탈로그)는 실제 컨테이너로 구동하며, 시더는 {@code test} 프로파일에서 비활성이라 이 테스트가
 * 직접 시드한다.
 */
@SpringBootTest
@ExtendWith(RestDocumentationExtension.class)
@ActiveProfiles("test")
@Testcontainers
@Import(TestcontainersConfiguration.class)
class QuizDocsTest {

  @Container @ServiceConnection static MongoDBContainer mongo = new MongoDBContainer("mongo:7.0");

  private static final String MALFORMED_BODY = "{ \"oops\" }";

  // 서명이 깨진 access 토큰 → 401 UNAUTHENTICATED. restdocs-api-spec 이 무인증 예시에서도 bearerAuthJWT 스킴을 도출하게
  // 한다.
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

  @Autowired private WebApplicationContext context;
  @Autowired private JwtTokenService jwtTokenService;
  @Autowired private JwtProperties jwtProperties;
  @Autowired private QuizMongoRepository quizMongoRepository;

  @MockitoBean private UserAccountService userAccountService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp(RestDocumentationContextProvider restDocumentation) {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .apply(springSecurity())
            .apply(documentationConfiguration(restDocumentation))
            .build();
    quizMongoRepository.deleteAll();
    // 표시 언어는 user 공개 query로 취득(ADR-0029) — 등록 국가(KR→ko)를 가정. 대상은 세입자(TENANT).
    given(userAccountService.getLanguage(anyLong())).willReturn("ko");
    given(userAccountService.getUserType(anyLong())).willReturn("TENANT");
  }

  @Test
  void generatesQuizSnippets() throws Exception {
    seedQuiz();
    String token = jwtTokenService.issueAccessToken(1L);

    // ① 랜덤 퀴즈 조회 — 사용자 언어로 번역, 정답·해설 미포함
    mockMvc
        .perform(get("/api/v1/quizzes/random").header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.quizId").value(4001))
        .andExpect(jsonPath("$.data.choices.length()").value(4))
        .andDo(
            document(
                "quiz-get-random",
                resourceDetails().summary("랜덤 퀴즈 조회 — 활성 풀에서 무작위 1개(등록 국가 언어로 번역, 정답·해설 미포함)"),
                responseFields(randomResponseFields())));

    // ② 정답 제출 — 정답: correct=true만(정답·해설 미노출)
    mockMvc
        .perform(
            post("/api/v1/quizzes/{quizId}/answer", 4001L)
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(answerJson("A")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.correct").value(true))
        .andDo(
            document(
                "quiz-answer-correct",
                resourceDetails().summary("정답 제출·채점 — 정답(correct=true, 무상태·정답/해설 미노출)"),
                pathParameters(parameterWithName("quizId").description("채점 대상 퀴즈 ID")),
                requestFields(answerRequestFields()),
                responseFields(answerCorrectResponseFields())));

    // ③ 정답 제출 — 오답: 정답 키(correctChoice)와 번역된 해설(explanation)
    mockMvc
        .perform(
            post("/api/v1/quizzes/{quizId}/answer", 4001L)
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(answerJson("B")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.correct").value(false))
        .andExpect(jsonPath("$.data.correctChoice").value("A"))
        .andDo(
            document(
                "quiz-answer-wrong",
                resourceDetails().summary("정답 제출·채점 — 오답(correct=false + 정답 키·번역된 오답 해설)"),
                pathParameters(parameterWithName("quizId").description("채점 대상 퀴즈 ID")),
                requestFields(answerRequestFields()),
                responseFields(answerWrongResponseFields())));
  }

  /** 스펙의 "발생 가능한 에러"를 엔드포인트별로 실제 트리거해 스니펫으로 생성하고 status·error.code를 단정한다. */
  @Test
  void generatesQuizErrorSnippets() throws Exception {
    String token = jwtTokenService.issueAccessToken(1L);
    String onboardingToken = jwtTokenService.issueOnboardingToken(2L); // ROLE_ONBOARDING
    String landlordToken = jwtTokenService.issueAccessToken(3L);
    String expiredToken = expiredAccessToken();
    given(userAccountService.getUserType(3L)).willReturn("LANDLORD");

    // ===== GET /quizzes/random =====
    perform(
        get("/api/v1/quizzes/random").header(HttpHeaders.AUTHORIZATION, bearer(FORGED_TOKEN)),
        status().isUnauthorized(),
        "UNAUTHENTICATED",
        "quiz-get-random-unauthenticated",
        "랜덤 퀴즈 조회 — 인증 누락/위조 (401 UNAUTHENTICATED)");

    perform(
        get("/api/v1/quizzes/random").header(HttpHeaders.AUTHORIZATION, bearer(expiredToken)),
        status().isUnauthorized(),
        "TOKEN_EXPIRED",
        "quiz-get-random-token-expired",
        "랜덤 퀴즈 조회 — 액세스 토큰 만료 (401 TOKEN_EXPIRED)");

    perform(
        get("/api/v1/quizzes/random").header(HttpHeaders.AUTHORIZATION, bearer(onboardingToken)),
        status().isForbidden(),
        "AUTH_ONBOARDING_REQUIRED",
        "quiz-get-random-onboarding-required",
        "랜덤 퀴즈 조회 — 온보딩 미완료(비-ACTIVE) 접근 (403 AUTH_ONBOARDING_REQUIRED)");

    perform(
        get("/api/v1/quizzes/random").header(HttpHeaders.AUTHORIZATION, bearer(landlordToken)),
        status().isForbidden(),
        "FORBIDDEN",
        "quiz-get-random-not-tenant",
        "랜덤 퀴즈 조회 — 세입자가 아님(임대인 등) (403 FORBIDDEN)");

    perform(
        get("/api/v1/quizzes/random").header(HttpHeaders.AUTHORIZATION, bearer(token)),
        status().isNotFound(),
        "QUIZ_NOT_FOUND",
        "quiz-get-random-not-found",
        "랜덤 퀴즈 조회 — 활성 퀴즈 풀이 비어 있음 (404 QUIZ_NOT_FOUND)");

    // ===== POST /quizzes/{quizId}/answer =====
    perform(
        post("/api/v1/quizzes/{quizId}/answer", 4001L)
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(answerJson("E")),
        status().isBadRequest(),
        "INVALID_INPUT",
        "quiz-answer-invalid-input",
        "정답 제출 — selectedChoice가 A~D가 아님/누락 (400 INVALID_INPUT)");

    perform(
        post("/api/v1/quizzes/{quizId}/answer", 4001L)
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(MALFORMED_BODY),
        status().isBadRequest(),
        "MALFORMED_REQUEST",
        "quiz-answer-malformed",
        "정답 제출 — 본문 해석 불가 (400 MALFORMED_REQUEST)");

    perform(
        post("/api/v1/quizzes/{quizId}/answer", 4001L)
            .header(HttpHeaders.AUTHORIZATION, bearer(FORGED_TOKEN))
            .contentType(MediaType.APPLICATION_JSON)
            .content(answerJson("A")),
        status().isUnauthorized(),
        "UNAUTHENTICATED",
        "quiz-answer-unauthenticated",
        "정답 제출 — 인증 누락/위조 (401 UNAUTHENTICATED)");

    perform(
        post("/api/v1/quizzes/{quizId}/answer", 4001L)
            .header(HttpHeaders.AUTHORIZATION, bearer(expiredToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content(answerJson("A")),
        status().isUnauthorized(),
        "TOKEN_EXPIRED",
        "quiz-answer-token-expired",
        "정답 제출 — 액세스 토큰 만료 (401 TOKEN_EXPIRED)");

    perform(
        post("/api/v1/quizzes/{quizId}/answer", 4001L)
            .header(HttpHeaders.AUTHORIZATION, bearer(onboardingToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content(answerJson("A")),
        status().isForbidden(),
        "AUTH_ONBOARDING_REQUIRED",
        "quiz-answer-onboarding-required",
        "정답 제출 — 온보딩 미완료(비-ACTIVE) 접근 (403 AUTH_ONBOARDING_REQUIRED)");

    perform(
        post("/api/v1/quizzes/{quizId}/answer", 9999L)
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(answerJson("A")),
        status().isNotFound(),
        "QUIZ_NOT_FOUND",
        "quiz-answer-not-found",
        "정답 제출 — 대상 퀴즈가 존재하지 않음 (404 QUIZ_NOT_FOUND)");
  }

  // ---- helpers ----

  private void perform(
      MockHttpServletRequestBuilder request,
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

  private static FieldDescriptor field(String path, JsonFieldType type, String description) {
    return fieldWithPath(path).type(type).description(description);
  }

  private static FieldDescriptor errorNull() {
    return fieldWithPath("error")
        .type(JsonFieldType.NULL)
        .optional()
        .description("성공 응답의 error는 항상 null");
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

  private static List<FieldDescriptor> randomResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"),
        field("data.quizId", JsonFieldType.NUMBER, "퀴즈 식별자(채점 시 경로로 사용)"),
        field("data.question", JsonFieldType.STRING, "등록 국가 언어로 번역된 문항"),
        field("data.choices[].key", JsonFieldType.STRING, "보기 키(A~D, 언어 무관)"),
        field("data.choices[].text", JsonFieldType.STRING, "번역된 보기 텍스트"),
        errorNull());
  }

  private static List<FieldDescriptor> answerRequestFields() {
    return List.of(field("selectedChoice", JsonFieldType.STRING, "선택한 보기 키(A~D 중 하나)"));
  }

  private static List<FieldDescriptor> answerCorrectResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"),
        field("data.quizId", JsonFieldType.NUMBER, "채점 대상 퀴즈 ID"),
        field("data.selectedChoice", JsonFieldType.STRING, "제출한 보기 키(A~D)"),
        field("data.correct", JsonFieldType.BOOLEAN, "정답 여부(정답이면 true) — 정답 시 정답·해설은 미노출"),
        errorNull());
  }

  private static List<FieldDescriptor> answerWrongResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"),
        field("data.quizId", JsonFieldType.NUMBER, "채점 대상 퀴즈 ID"),
        field("data.selectedChoice", JsonFieldType.STRING, "제출한 보기 키(A~D)"),
        field("data.correct", JsonFieldType.BOOLEAN, "정답 여부(오답이면 false)"),
        field("data.correctChoice", JsonFieldType.STRING, "정답 보기 키(오답 시에만)"),
        field("data.explanation", JsonFieldType.STRING, "오답 사유·해설(오답 시에만, 사용자 언어 번역)"),
        errorNull());
  }

  private void seedQuiz() {
    quizMongoRepository.save(
        QuizDocument.builder()
            .id(4001L)
            .active(true)
            .question(Map.of("en", "Which protects a jeonse tenant?", "ko", "전세 임차인 보호 제도는?"))
            .choices(
                List.of(
                    ChoiceSpec.builder()
                        .key("A")
                        .text(Map.of("en", "Fixed date", "ko", "확정일자"))
                        .build(),
                    ChoiceSpec.builder().key("B").text(Map.of("en", "Fee", "ko", "관리비")).build(),
                    ChoiceSpec.builder().key("C").text(Map.of("en", "Loan", "ko", "대출")).build(),
                    ChoiceSpec.builder().key("D").text(Map.of("en", "Tax", "ko", "세금")).build()))
            .correctChoice("A")
            .explanation(
                Map.of("en", "A fixed date protects your deposit.", "ko", "확정일자를 받으면 보증금을 보호받습니다."))
            .build());
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

  private static String bearer(String token) {
    return "Bearer " + token;
  }

  private static String answerJson(String selectedChoice) {
    return "{\"selectedChoice\":\"" + selectedChoice + "\"}";
  }
}
