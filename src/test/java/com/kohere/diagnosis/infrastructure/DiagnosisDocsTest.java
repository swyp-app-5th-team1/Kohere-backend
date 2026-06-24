package com.kohere.diagnosis.infrastructure;

import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.document;
import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.resourceDetails;
import static com.epages.restdocs.apispec.ResourceDocumentation.resource;
import static org.mockito.ArgumentMatchers.any;
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
import static org.springframework.restdocs.request.RequestDocumentation.queryParameters;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.epages.restdocs.apispec.ResourceSnippetParameters;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kohere.TestcontainersConfiguration;
import com.kohere.common.response.PageInfo;
import com.kohere.common.response.PageResponse;
import com.kohere.common.security.JwtProperties;
import com.kohere.common.security.JwtTokenService;
import com.kohere.diagnosis.infrastructure.DiagnosisQuestionDocument.OptionSpec;
import com.kohere.diagnosis.infrastructure.DiagnosisQuestionDocument.SelectSpec;
import com.kohere.diagnosis.infrastructure.DiagnosisSuggestionDocument.ActionSpec;
import com.kohere.listing.api.ListingRecommendationService;
import com.kohere.listing.api.RecommendedListingView;
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
import org.springframework.restdocs.request.ParameterDescriptor;
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
 * Spring REST Docs 스니펫 생성 테스트(ADR-0007·0016). 진단·추천 엔드포인트(단계별 질문 조회·단계 답 저장·확정·이력·최근·단건 상세·추천)의 성공
 * 응답과, 스펙(02-diagnosis-recommendation.md)에 정의된 주요 에러 응답을 {@code build/generated-snippets}에 생성한다 —
 * auth-onboarding과 동일한 방식으로 OpenAPI3 명세(Swagger UI)에 합류한다.
 *
 * <p>cross-module 협력(listing 추천·user 표시 언어)은 {@code @MockitoBean}으로 대체하고 access 토큰은 {@link
 * JwtTokenService}로 직접 발급한다(test-strategy §4, 통합 테스트 {@link DiagnosisMongoIntegrationTest}와 동일 전략).
 * MongoDB(문항·제안 카탈로그·진단 영속)는 실제 컨테이너로, Security·JPA·Redis 컨텍스트는 실제로 구동한다. 진단 문항·제안 시더는 {@code test}
 * 프로파일에서 비활성이라 이 테스트가 직접 카탈로그를 시드한다.
 */
@SpringBootTest
@ExtendWith(RestDocumentationExtension.class)
@ActiveProfiles("test")
@Testcontainers
@Import(TestcontainersConfiguration.class)
class DiagnosisDocsTest {

  @Container @ServiceConnection static MongoDBContainer mongo = new MongoDBContainer("mongo:7.0");

  private static final String MALFORMED_BODY = "{ \"oops\" }";

  // 서명이 깨진(다른 키로 서명) access 토큰. 서버 검증에서 401 UNAUTHENTICATED 를 유발하면서도 구조상 JWT 라,
  // restdocs-api-spec 이 무인증 예시에서도 bearerAuthJWT 보안 스킴을 도출하게 한다(모든 예시가 Bearer JWT 헤더를
  // 갖게 해 비결정적 스니펫 병합 순서와 무관하게 Swagger 자물쇠가 유지된다 — auth 문서화와 동일 처리).
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
  @Autowired private DiagnosisMongoRepository diagnosisMongoRepository;
  @Autowired private DiagnosisQuestionMongoRepository questionMongoRepository;
  @Autowired private DiagnosisSuggestionMongoRepository suggestionMongoRepository;

  @MockitoBean private UserAccountService userAccountService;
  @MockitoBean private ListingRecommendationService listingRecommendationService;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private MockMvc mockMvc;

  @BeforeEach
  void setUp(RestDocumentationContextProvider restDocumentation) {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .apply(springSecurity())
            .apply(documentationConfiguration(restDocumentation))
            .build();
    diagnosisMongoRepository.deleteAll();
    questionMongoRepository.deleteAll();
    suggestionMongoRepository.deleteAll();
    seedQuestions();
    seedSuggestion();
    // 표시 언어는 user 공개 query로 취득(ADR-0029) — 등록 국가(KR→ko)를 가정해 한국어 라벨을 내려받는다.
    given(userAccountService.getLanguage(anyLong())).willReturn("ko");
  }

  @Test
  void generatesDiagnosisSnippets() throws Exception {
    long userId = 1L;
    String token = jwtTokenService.issueAccessToken(userId);

    // ① 단계별 질문 조회(step 1 = region) — 등록 국가 언어 번역
    mockMvc
        .perform(
            get("/api/v1/diagnoses/questions/{step}", 1)
                .header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.field").value("region"))
        .andDo(
            document(
                "diagnosis-get-question",
                resourceDetails()
                    .summary("단계별 질문 조회 — step(1~6) 질문 1개·선택지 반환(등록 국가 언어로 라벨 번역, 미지원 언어는 영어 폴백)"),
                pathParameters(parameterWithName("step").description("조회할 단계(1~6)")),
                responseFields(questionResponseFields())));

    // 답 저장(단일 선택) — region
    mockMvc
        .perform(
            post("/api/v1/diagnoses/answers")
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(answerJson("region", "SEOUL")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.saved").value(true))
        .andDo(
            document(
                "diagnosis-submit-answer",
                resourceDetails()
                    .summary("단계 답 저장 — 현재 단계 답 1개(field+code)를 진행 중(IN_PROGRESS) 진단에 저장"),
                requestFields(answerSingleRequestFields()),
                responseFields(answerSavedResponseFields())));

    // 답 저장 — purpose(③ 단계 서버 분기의 입력이 된다)
    saveAnswer(token, answerJson("purpose", "STUDY"));

    // ① 단계별 질문 조회(step 3) — 서버가 저장된 purpose=STUDY로 university 문항을 선정(클라 분기 아님)
    mockMvc
        .perform(
            get("/api/v1/diagnoses/questions/{step}", 3)
                .header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.field").value("university"))
        .andDo(
            document(
                "diagnosis-get-question-step3",
                resourceDetails()
                    .summary(
                        "단계별 질문 조회 — ③(step 3)은 서버가 저장된 purpose로 대학(STUDY)/지역구(NON_STUDY) 중 하나만 선정"),
                pathParameters(parameterWithName("step").description("조회할 단계(1~6)")),
                responseFields(questionResponseFields())));

    saveAnswer(token, answerJson("university", "SNU"));

    // 답 저장(다중 선택) — conditions 는 codes 배열로 전송
    mockMvc
        .perform(
            post("/api/v1/diagnoses/answers")
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"field\":\"conditions\",\"codes\":[\"FEMALE_ONLY\",\"PRIVATE_BATH\"]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.saved").value(true))
        .andDo(
            document(
                "diagnosis-submit-answer-multi",
                resourceDetails().summary("단계 답 저장 — 다중 선택(④ conditions, 최대 3개)은 codes 배열로 전송"),
                requestFields(answerCodesRequestFields()),
                responseFields(answerSavedResponseFields())));

    saveAnswer(token, answerJson("monthlyBudgetMax", "600000"));
    saveAnswer(token, answerJson("arcStatus", "ARC_ISSUED"));

    // ③ 진행 중 진단 확정 → 201 Created (Location 헤더 포함)
    String created =
        mockMvc
            .perform(post("/api/v1/diagnoses").header(HttpHeaders.AUTHORIZATION, bearer(token)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.status").value("COMPLETED"))
            .andDo(
                document(
                    "diagnosis-submit",
                    resourceDetails()
                        .summary(
                            "진행 중 진단 확정 — IN_PROGRESS→COMPLETED, diagnosisId·submittedAt 발급"
                                + "(Location: /api/v1/diagnoses/{diagnosisId})"),
                    responseFields(createdResponseFields())))
            .andReturn()
            .getResponse()
            .getContentAsString();
    long diagnosisId = Long.parseLong(read(created, "data", "diagnosisId"));

    // ④ 내 진단 이력 목록(오프셋 페이지네이션)
    mockMvc
        .perform(
            get("/api/v1/diagnoses")
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .param("page", "0")
                .param("size", "20")
                .param("sort", "submittedAt,desc"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[0].diagnosisId").value(diagnosisId))
        .andDo(
            document(
                "diagnosis-history",
                resourceDetails().summary("내 진단 이력 목록 — 확정(COMPLETED) 진단만, 오프셋 페이지네이션"),
                queryParameters(historyQueryParameters()),
                responseFields(historyResponseFields())));

    // ⑤ 최근 진단 단건(홈 완료 여부 분기)
    mockMvc
        .perform(get("/api/v1/diagnoses/latest").header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.completed").value(true))
        .andDo(
            document(
                "diagnosis-latest",
                resourceDetails()
                    .summary("최근 진단 단건 — 최근 확정 진단 1건(이력 없으면 completed=false만 반환, 404 아님)"),
                responseFields(latestResponseFields())));

    // ⑥ 진단 단건 상세(입력 다시 보기, 본인 소유만)
    mockMvc
        .perform(
            get("/api/v1/diagnoses/{diagnosisId}", diagnosisId)
                .header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.diagnosisId").value(diagnosisId))
        .andDo(
            document(
                "diagnosis-detail",
                resourceDetails().summary("진단 단건 상세 — 진단 입력 전체(본인 소유만)"),
                pathParameters(parameterWithName("diagnosisId").description("진단 식별자(본인 소유)")),
                responseFields(detailResponseFields())));

    // ⑦ 추천 결과(매물 + 지도 좌표) — 결과 있음
    given(listingRecommendationService.recommendByCriteria(any()))
        .willReturn(
            pageOf(
                new RecommendedListingView(
                    5001L,
                    "Sinchon Co-living House A",
                    "CO_LIVING",
                    550000,
                    1_000_000,
                    "https://cdn.kohere.app/listings/5001/thumb.jpg",
                    37.555134,
                    126.936893,
                    List.of("FEMALE_ONLY", "PRIVATE_BATH"))));
    mockMvc
        .perform(
            get("/api/v1/diagnoses/{diagnosisId}/recommendations", diagnosisId)
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .param("page", "0")
                .param("size", "20")
                .param("sort", "recommended,desc"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[0].listingId").value(5001))
        .andDo(
            document(
                "diagnosis-recommendations",
                resourceDetails().summary("진단 결과 추천 — 매물 요약 + 지도 마커(결과 있음)"),
                pathParameters(parameterWithName("diagnosisId").description("진단 식별자(본인 소유)")),
                queryParameters(recommendationQueryParameters()),
                responseFields(recommendationWithContentFields())));

    // ⑦ 추천 결과 — 0건: 빈 목록 + 번역된 조정 제안(suggestions)
    given(listingRecommendationService.recommendByCriteria(any())).willReturn(emptyPage());
    mockMvc
        .perform(
            get("/api/v1/diagnoses/{diagnosisId}/recommendations", diagnosisId)
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .param("page", "0")
                .param("size", "20")
                .param("sort", "recommended,desc"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content").isEmpty())
        .andExpect(jsonPath("$.data.suggestions.reason").value("NO_MATCH"))
        .andDo(
            document(
                "diagnosis-recommendations-no-match",
                resourceDetails()
                    .summary(
                        "진단 결과 추천 — 0건: 빈 목록 + 조정 제안(reason/type은 enum, message/detail은 사용자 언어 번역)"),
                pathParameters(parameterWithName("diagnosisId").description("진단 식별자(본인 소유)")),
                queryParameters(recommendationQueryParameters()),
                responseFields(recommendationNoMatchFields())));
  }

  /** 스펙의 "발생 가능한 에러"를 엔드포인트별로 실제 트리거해 스니펫으로 생성하고 status·error.code를 단정한다. */
  @Test
  void generatesDiagnosisErrorSnippets() throws Exception {
    long owner = 100L;
    long stranger = 101L;
    String ownerToken = jwtTokenService.issueAccessToken(owner);
    String strangerToken = jwtTokenService.issueAccessToken(stranger);
    String freshToken = jwtTokenService.issueAccessToken(102L); // 진행 중 진단이 없는 사용자
    String expiredToken = expiredAccessToken();

    long ownedId = createCompletedDiagnosis(ownerToken);
    long missingId = 9_999_999L;

    // ===== GET /questions/{step} =====
    perform(
        get("/api/v1/diagnoses/questions/{step}", 7)
            .header(HttpHeaders.AUTHORIZATION, bearer(ownerToken)),
        status().isBadRequest(),
        "INVALID_INPUT",
        "diagnosis-get-question-out-of-range",
        "단계별 질문 조회 — step 범위 밖(1~6 아님) (400 INVALID_INPUT)");

    perform(
        get("/api/v1/diagnoses/questions/{step}", 3)
            .header(HttpHeaders.AUTHORIZATION, bearer(freshToken)),
        status().isBadRequest(),
        "INVALID_INPUT",
        "diagnosis-get-question-purpose-required",
        "단계별 질문 조회 — ③(step 3)인데 purpose 미선행 (400 INVALID_INPUT)");

    perform(
        get("/api/v1/diagnoses/questions/{step}", 1)
            .header(HttpHeaders.AUTHORIZATION, bearer(FORGED_TOKEN)),
        status().isUnauthorized(),
        "UNAUTHENTICATED",
        "diagnosis-get-question-unauthenticated",
        "단계별 질문 조회 — 인증 누락/위조 (401 UNAUTHENTICATED)");

    perform(
        get("/api/v1/diagnoses/questions/{step}", 1)
            .header(HttpHeaders.AUTHORIZATION, bearer(expiredToken)),
        status().isUnauthorized(),
        "TOKEN_EXPIRED",
        "diagnosis-get-question-token-expired",
        "단계별 질문 조회 — 액세스 토큰 만료 (401 TOKEN_EXPIRED)");

    // ===== POST /answers =====
    perform(
        post("/api/v1/diagnoses/answers")
            .header(HttpHeaders.AUTHORIZATION, bearer(ownerToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content(answerJson("region", "MARS")),
        status().isBadRequest(),
        "INVALID_INPUT",
        "diagnosis-submit-answer-invalid-input",
        "단계 답 저장 — 미정의 enum/현재 단계 불일치 등 (400 INVALID_INPUT)");

    perform(
        post("/api/v1/diagnoses/answers")
            .header(HttpHeaders.AUTHORIZATION, bearer(ownerToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content(MALFORMED_BODY),
        status().isBadRequest(),
        "MALFORMED_REQUEST",
        "diagnosis-submit-answer-malformed",
        "단계 답 저장 — 본문 해석 불가 (400 MALFORMED_REQUEST)");

    perform(
        post("/api/v1/diagnoses/answers")
            .header(HttpHeaders.AUTHORIZATION, bearer(FORGED_TOKEN))
            .contentType(MediaType.APPLICATION_JSON)
            .content(answerJson("region", "SEOUL")),
        status().isUnauthorized(),
        "UNAUTHENTICATED",
        "diagnosis-submit-answer-unauthenticated",
        "단계 답 저장 — 인증 누락/위조 (401 UNAUTHENTICATED)");

    perform(
        post("/api/v1/diagnoses/answers")
            .header(HttpHeaders.AUTHORIZATION, bearer(expiredToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content(answerJson("region", "SEOUL")),
        status().isUnauthorized(),
        "TOKEN_EXPIRED",
        "diagnosis-submit-answer-token-expired",
        "단계 답 저장 — 액세스 토큰 만료 (401 TOKEN_EXPIRED)");

    // ===== POST /diagnoses (확정) =====
    perform(
        post("/api/v1/diagnoses").header(HttpHeaders.AUTHORIZATION, bearer(freshToken)),
        status().isBadRequest(),
        "INVALID_INPUT",
        "diagnosis-submit-no-draft",
        "진단 확정 — 진행 중 진단 없음/단계 미완료 (400 INVALID_INPUT)");

    perform(
        post("/api/v1/diagnoses").header(HttpHeaders.AUTHORIZATION, bearer(FORGED_TOKEN)),
        status().isUnauthorized(),
        "UNAUTHENTICATED",
        "diagnosis-submit-unauthenticated",
        "진단 확정 — 인증 누락/위조 (401 UNAUTHENTICATED)");

    perform(
        post("/api/v1/diagnoses").header(HttpHeaders.AUTHORIZATION, bearer(expiredToken)),
        status().isUnauthorized(),
        "TOKEN_EXPIRED",
        "diagnosis-submit-token-expired",
        "진단 확정 — 액세스 토큰 만료 (401 TOKEN_EXPIRED)");

    // ===== GET /diagnoses (이력) =====
    perform(
        get("/api/v1/diagnoses")
            .header(HttpHeaders.AUTHORIZATION, bearer(ownerToken))
            .param("sort", "unknownKey,desc"),
        status().isBadRequest(),
        "INVALID_INPUT",
        "diagnosis-history-invalid-input",
        "진단 이력 — 허용되지 않은 sort 키/size 범위 초과 (400 INVALID_INPUT)");

    perform(
        get("/api/v1/diagnoses").header(HttpHeaders.AUTHORIZATION, bearer(FORGED_TOKEN)),
        status().isUnauthorized(),
        "UNAUTHENTICATED",
        "diagnosis-history-unauthenticated",
        "진단 이력 — 인증 누락/위조 (401 UNAUTHENTICATED)");

    perform(
        get("/api/v1/diagnoses").header(HttpHeaders.AUTHORIZATION, bearer(expiredToken)),
        status().isUnauthorized(),
        "TOKEN_EXPIRED",
        "diagnosis-history-token-expired",
        "진단 이력 — 액세스 토큰 만료 (401 TOKEN_EXPIRED)");

    // ===== GET /diagnoses/latest =====
    perform(
        get("/api/v1/diagnoses/latest").header(HttpHeaders.AUTHORIZATION, bearer(FORGED_TOKEN)),
        status().isUnauthorized(),
        "UNAUTHENTICATED",
        "diagnosis-latest-unauthenticated",
        "최근 진단 — 인증 누락/위조 (401 UNAUTHENTICATED)");

    perform(
        get("/api/v1/diagnoses/latest").header(HttpHeaders.AUTHORIZATION, bearer(expiredToken)),
        status().isUnauthorized(),
        "TOKEN_EXPIRED",
        "diagnosis-latest-token-expired",
        "최근 진단 — 액세스 토큰 만료 (401 TOKEN_EXPIRED)");

    // ===== GET /diagnoses/{id} =====
    perform(
        get("/api/v1/diagnoses/{diagnosisId}", ownedId)
            .header(HttpHeaders.AUTHORIZATION, bearer(strangerToken)),
        status().isForbidden(),
        "FORBIDDEN",
        "diagnosis-detail-forbidden",
        "진단 단건 상세 — 타인 소유 진단 접근 (403 FORBIDDEN)");

    perform(
        get("/api/v1/diagnoses/{diagnosisId}", missingId)
            .header(HttpHeaders.AUTHORIZATION, bearer(ownerToken)),
        status().isNotFound(),
        "DIAGNOSIS_NOT_FOUND",
        "diagnosis-detail-not-found",
        "진단 단건 상세 — 진단이 존재하지 않음 (404 DIAGNOSIS_NOT_FOUND)");

    perform(
        get("/api/v1/diagnoses/{diagnosisId}", ownedId)
            .header(HttpHeaders.AUTHORIZATION, bearer(FORGED_TOKEN)),
        status().isUnauthorized(),
        "UNAUTHENTICATED",
        "diagnosis-detail-unauthenticated",
        "진단 단건 상세 — 인증 누락/위조 (401 UNAUTHENTICATED)");

    perform(
        get("/api/v1/diagnoses/{diagnosisId}", ownedId)
            .header(HttpHeaders.AUTHORIZATION, bearer(expiredToken)),
        status().isUnauthorized(),
        "TOKEN_EXPIRED",
        "diagnosis-detail-token-expired",
        "진단 단건 상세 — 액세스 토큰 만료 (401 TOKEN_EXPIRED)");

    // ===== GET /diagnoses/{id}/recommendations =====
    perform(
        get("/api/v1/diagnoses/{diagnosisId}/recommendations", ownedId)
            .header(HttpHeaders.AUTHORIZATION, bearer(strangerToken)),
        status().isForbidden(),
        "FORBIDDEN",
        "diagnosis-recommendations-forbidden",
        "진단 결과 추천 — 타인 소유 진단 접근 (403 FORBIDDEN)");

    perform(
        get("/api/v1/diagnoses/{diagnosisId}/recommendations", missingId)
            .header(HttpHeaders.AUTHORIZATION, bearer(ownerToken)),
        status().isNotFound(),
        "DIAGNOSIS_NOT_FOUND",
        "diagnosis-recommendations-not-found",
        "진단 결과 추천 — 진단이 존재하지 않음 (404 DIAGNOSIS_NOT_FOUND)");

    perform(
        get("/api/v1/diagnoses/{diagnosisId}/recommendations", ownedId)
            .header(HttpHeaders.AUTHORIZATION, bearer(ownerToken))
            .param("sort", "unknownKey,desc"),
        status().isBadRequest(),
        "INVALID_INPUT",
        "diagnosis-recommendations-invalid-input",
        "진단 결과 추천 — 허용되지 않은 sort 키/size 범위 초과 (400 INVALID_INPUT)");

    perform(
        get("/api/v1/diagnoses/{diagnosisId}/recommendations", ownedId)
            .header(HttpHeaders.AUTHORIZATION, bearer(FORGED_TOKEN)),
        status().isUnauthorized(),
        "UNAUTHENTICATED",
        "diagnosis-recommendations-unauthenticated",
        "진단 결과 추천 — 인증 누락/위조 (401 UNAUTHENTICATED)");

    perform(
        get("/api/v1/diagnoses/{diagnosisId}/recommendations", ownedId)
            .header(HttpHeaders.AUTHORIZATION, bearer(expiredToken)),
        status().isUnauthorized(),
        "TOKEN_EXPIRED",
        "diagnosis-recommendations-token-expired",
        "진단 결과 추천 — 액세스 토큰 만료 (401 TOKEN_EXPIRED)");
  }

  // ---- helpers (flow) ----

  private void saveAnswer(String token, String body) throws Exception {
    mockMvc
        .perform(
            post("/api/v1/diagnoses/answers")
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());
  }

  /** 한 사용자의 진단을 단계 답 저장 → 확정까지 수행하고 발급된 diagnosisId를 돌려준다(STUDY 흐름). */
  private long createCompletedDiagnosis(String token) throws Exception {
    saveAnswer(token, answerJson("region", "SEOUL"));
    saveAnswer(token, answerJson("purpose", "STUDY"));
    saveAnswer(token, answerJson("university", "SNU"));
    saveAnswer(token, "{\"field\":\"conditions\",\"codes\":[\"FEMALE_ONLY\"]}");
    saveAnswer(token, answerJson("monthlyBudgetMax", "500000"));
    saveAnswer(token, answerJson("arcStatus", "ARC_ISSUED"));
    String created =
        mockMvc
            .perform(post("/api/v1/diagnoses").header(HttpHeaders.AUTHORIZATION, bearer(token)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return Long.parseLong(read(created, "data", "diagnosisId"));
  }

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

  // ---- field descriptors ----

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

  private static List<FieldDescriptor> questionResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"),
        field("data.step", JsonFieldType.NUMBER, "질문 단계(1~6)"),
        field(
            "data.field",
            JsonFieldType.STRING,
            "제출 필드명(region·purpose·university·district·conditions 등)"),
        field("data.question", JsonFieldType.STRING, "등록 국가 언어로 번역된 질문 라벨"),
        field("data.select.type", JsonFieldType.STRING, "선택 방식(SINGLE|MULTI|NUMBER)"),
        field("data.select.max", JsonFieldType.NUMBER, "최대 선택 개수(MULTI는 3)"),
        field("data.options[].code", JsonFieldType.STRING, "선택지 코드(enum, 언어 무관 동일)"),
        field("data.options[].label", JsonFieldType.STRING, "번역된 표시 라벨"),
        errorNull());
  }

  private static List<FieldDescriptor> answerSingleRequestFields() {
    return List.of(
        field("field", JsonFieldType.STRING, "답을 저장할 제출 필드명"),
        field("code", JsonFieldType.STRING, "단일 선택 답의 코드(enum 이름)"));
  }

  private static List<FieldDescriptor> answerCodesRequestFields() {
    return List.of(
        field("field", JsonFieldType.STRING, "답을 저장할 제출 필드명(다중 선택, 예: conditions)"),
        field("codes", JsonFieldType.ARRAY, "다중 선택 답의 코드 집합(최대 3개, 중복 불가)"));
  }

  private static List<FieldDescriptor> answerSavedResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"),
        field("data.saved", JsonFieldType.BOOLEAN, "진행 중 진단에 저장됨(항상 true)"),
        errorNull());
  }

  private static List<FieldDescriptor> createdResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"),
        field("data.diagnosisId", JsonFieldType.NUMBER, "확정된 진단 식별자"),
        field("data.status", JsonFieldType.STRING, "진단 상태(COMPLETED)"),
        field("data.submittedAt", JsonFieldType.STRING, "확정(제출) 시각(ISO-8601 UTC)"),
        errorNull());
  }

  private static List<FieldDescriptor> diagnosisSummaryFields(String prefix) {
    return List.of(
        field(prefix + "diagnosisId", JsonFieldType.NUMBER, "진단 식별자"),
        field(prefix + "region", JsonFieldType.STRING, "지역(SEOUL|BUSAN|GYEONGGI)"),
        field(prefix + "purpose", JsonFieldType.STRING, "입국 목적(STUDY|NON_STUDY)"),
        optField(prefix + "university", JsonFieldType.STRING, "대학(STUDY일 때만, NON_STUDY면 null)"),
        optField(prefix + "district", JsonFieldType.STRING, "지역구(NON_STUDY일 때만, STUDY면 null)"),
        field(prefix + "conditions", JsonFieldType.ARRAY, "주거 조건 코드 목록(0~3개)"),
        field(prefix + "monthlyBudgetMax", JsonFieldType.NUMBER, "월 예산 상한(KRW)"),
        field(prefix + "arcStatus", JsonFieldType.STRING, "ARC 발급 상태(ARC_ISSUED|ARC_PENDING)"));
  }

  private static List<FieldDescriptor> historyResponseFields() {
    java.util.ArrayList<FieldDescriptor> fields = new java.util.ArrayList<>();
    fields.add(field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"));
    fields.addAll(diagnosisSummaryFields("data.content[]."));
    fields.add(field("data.content[].status", JsonFieldType.STRING, "진단 상태(COMPLETED)"));
    fields.add(field("data.content[].submittedAt", JsonFieldType.STRING, "확정 시각(ISO-8601 UTC)"));
    fields.add(field("data.page.number", JsonFieldType.NUMBER, "현재 페이지 번호(0-base)"));
    fields.add(field("data.page.size", JsonFieldType.NUMBER, "페이지 크기"));
    fields.add(field("data.page.totalElements", JsonFieldType.NUMBER, "전체 건수"));
    fields.add(field("data.page.totalPages", JsonFieldType.NUMBER, "전체 페이지 수"));
    fields.add(field("data.page.hasNext", JsonFieldType.BOOLEAN, "다음 페이지 존재 여부"));
    fields.add(errorNull());
    return fields;
  }

  private static List<FieldDescriptor> detailResponseFields() {
    java.util.ArrayList<FieldDescriptor> fields = new java.util.ArrayList<>();
    fields.add(field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"));
    fields.addAll(diagnosisSummaryFields("data."));
    fields.add(field("data.status", JsonFieldType.STRING, "진단 상태(COMPLETED)"));
    fields.add(field("data.submittedAt", JsonFieldType.STRING, "확정 시각(ISO-8601 UTC)"));
    fields.add(errorNull());
    return fields;
  }

  private static List<FieldDescriptor> latestResponseFields() {
    java.util.ArrayList<FieldDescriptor> fields = new java.util.ArrayList<>();
    fields.add(field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"));
    fields.add(field("data.completed", JsonFieldType.BOOLEAN, "확정 진단 존재 여부(false면 요약 필드 생략)"));
    fields.addAll(diagnosisSummaryFields("data."));
    fields.add(field("data.submittedAt", JsonFieldType.STRING, "확정 시각(ISO-8601 UTC)"));
    fields.add(errorNull());
    return fields;
  }

  private static List<FieldDescriptor> recommendationWithContentFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"),
        field("data.content[].listingId", JsonFieldType.NUMBER, "매물 식별자"),
        field("data.content[].title", JsonFieldType.STRING, "매물 제목"),
        field("data.content[].type", JsonFieldType.STRING, "주거 유형(원시 문자열)"),
        field("data.content[].monthlyRent", JsonFieldType.NUMBER, "월세(KRW)"),
        field("data.content[].deposit", JsonFieldType.NUMBER, "보증금(KRW)"),
        optField("data.content[].thumbnailUrl", JsonFieldType.STRING, "썸네일 URL(없으면 null)"),
        field("data.content[].lat", JsonFieldType.NUMBER, "위도(WGS84)"),
        field("data.content[].lng", JsonFieldType.NUMBER, "경도(WGS84)"),
        field("data.content[].conditions", JsonFieldType.ARRAY, "매물 조건 코드 목록(원시 문자열)"),
        field("data.markers[].listingId", JsonFieldType.NUMBER, "마커 매물 식별자"),
        field("data.markers[].lat", JsonFieldType.NUMBER, "마커 위도"),
        field("data.markers[].lng", JsonFieldType.NUMBER, "마커 경도"),
        field("data.page.number", JsonFieldType.NUMBER, "현재 페이지 번호(0-base)"),
        field("data.page.size", JsonFieldType.NUMBER, "페이지 크기"),
        field("data.page.totalElements", JsonFieldType.NUMBER, "전체 건수"),
        field("data.page.totalPages", JsonFieldType.NUMBER, "전체 페이지 수"),
        field("data.page.hasNext", JsonFieldType.BOOLEAN, "다음 페이지 존재 여부"),
        optField("data.suggestions", JsonFieldType.NULL, "조정 제안 — 결과가 있으면 null"),
        errorNull());
  }

  private static List<FieldDescriptor> recommendationNoMatchFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"),
        field("data.content", JsonFieldType.ARRAY, "추천 매물 목록(0건이면 빈 배열)"),
        field("data.markers", JsonFieldType.ARRAY, "지도 마커(0건이면 빈 배열)"),
        field("data.page.number", JsonFieldType.NUMBER, "현재 페이지 번호(0-base)"),
        field("data.page.size", JsonFieldType.NUMBER, "페이지 크기"),
        field("data.page.totalElements", JsonFieldType.NUMBER, "전체 건수(0)"),
        field("data.page.totalPages", JsonFieldType.NUMBER, "전체 페이지 수(0)"),
        field("data.page.hasNext", JsonFieldType.BOOLEAN, "다음 페이지 존재 여부"),
        field("data.suggestions.reason", JsonFieldType.STRING, "조정 제안 사유(enum, 예: NO_MATCH)"),
        field("data.suggestions.message", JsonFieldType.STRING, "사용자 언어로 번역된 안내 메시지"),
        field("data.suggestions.actions[].type", JsonFieldType.STRING, "조정 액션 타입(enum, 언어 무관)"),
        field("data.suggestions.actions[].detail", JsonFieldType.STRING, "번역된 액션 설명"),
        errorNull());
  }

  // ---- query parameter descriptors (varargs — RequestDocumentation has no List overload) ----

  private static ParameterDescriptor[] historyQueryParameters() {
    return new ParameterDescriptor[] {
      parameterWithName("page").optional().description("0-base 페이지 번호(기본 0)"),
      parameterWithName("size").optional().description("페이지 크기(기본 20, 최대 100)"),
      parameterWithName("sort")
          .optional()
          .description("정렬 — field,(asc|desc). 허용 키: submittedAt(기본 submittedAt,desc)")
    };
  }

  private static ParameterDescriptor[] recommendationQueryParameters() {
    return new ParameterDescriptor[] {
      parameterWithName("page").optional().description("0-base 페이지 번호(기본 0)"),
      parameterWithName("size").optional().description("페이지 크기(기본 20, 최대 100)"),
      parameterWithName("sort")
          .optional()
          .description(
              "정렬 — field,(asc|desc). 허용 키: recommended|price|distance(기본 recommended,desc)")
    };
  }

  // ---- seed / fixtures ----

  private void seedQuestions() {
    questionMongoRepository.save(
        question(
            1,
            "region",
            "SINGLE",
            1,
            Map.of("en", "Which region will you live in?", "ko", "어느 지역에서 거주할 예정인가요?"),
            List.of(
                option("SEOUL", "Seoul", "서울"),
                option("BUSAN", "Busan", "부산"),
                option("GYEONGGI", "Gyeonggi", "경기"))));
    questionMongoRepository.save(
        question(
            3,
            "university",
            "SINGLE",
            1,
            Map.of("en", "Which university do you attend?", "ko", "어느 대학교에 다니나요?"),
            List.of(
                option("SNU", "Seoul National University", "서울대학교"),
                option("CAU", "Chung-Ang University", "중앙대학교"))));
    questionMongoRepository.save(
        question(
            3,
            "district",
            "SINGLE",
            1,
            Map.of("en", "Which district will you live in?", "ko", "어느 지역(구)에서 거주할 예정인가요?"),
            List.of(option("GURO_GU", "Guro-gu", "구로구"), option("GWANAK_GU", "Gwanak-gu", "관악구"))));
  }

  private void seedSuggestion() {
    suggestionMongoRepository.save(
        DiagnosisSuggestionDocument.builder()
            .id("NO_MATCH")
            .message(
                Map.of(
                    "en", "No listings matched your criteria. Try adjusting the options below.",
                    "ko", "조건에 맞는 매물이 없습니다. 아래 항목을 조정해 보세요."))
            .actions(
                List.of(
                    suggestionAction("RELAX_REGION", "Widen the region.", "지역 범위를 넓혀 보세요."),
                    suggestionAction(
                        "RELAX_CONDITIONS", "Reduce housing conditions.", "주거 조건을 줄여 보세요."),
                    suggestionAction(
                        "INCREASE_BUDGET", "Increase the monthly budget.", "월 예산을 높여 보세요.")))
            .build());
  }

  private static DiagnosisQuestionDocument question(
      int step,
      String field,
      String selectType,
      int max,
      Map<String, String> question,
      List<OptionSpec> options) {
    return DiagnosisQuestionDocument.builder()
        .step(step)
        .field(field)
        .active(true)
        .question(question)
        .select(SelectSpec.builder().type(selectType).max(max).build())
        .options(options)
        .build();
  }

  private static OptionSpec option(String code, String en, String ko) {
    return OptionSpec.builder().code(code).label(Map.of("en", en, "ko", ko)).build();
  }

  private static ActionSpec suggestionAction(String type, String en, String ko) {
    return ActionSpec.builder().type(type).detail(Map.of("en", en, "ko", ko)).build();
  }

  private static PageResponse<RecommendedListingView> emptyPage() {
    return PageResponse.of(List.of(), new PageInfo(0, 20, 0L, 0, false));
  }

  private static PageResponse<RecommendedListingView> pageOf(RecommendedListingView... views) {
    List<RecommendedListingView> content = List.of(views);
    return PageResponse.of(content, new PageInfo(0, 20, content.size(), 1, false));
  }

  // ---- token / json helpers ----

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

  private static String answerJson(String field, String code) {
    return "{\"field\":\"" + field + "\",\"code\":\"" + code + "\"}";
  }
}
