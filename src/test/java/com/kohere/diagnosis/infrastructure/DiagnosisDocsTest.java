package com.kohere.diagnosis.infrastructure;

import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.document;
import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.resourceDetails;
import static com.kohere.docs.ApiDocsErrors.errorSnippet;
import static com.kohere.docs.DiagnosisDocsFields.DETAIL_401;
import static com.kohere.docs.DiagnosisDocsFields.DETAIL_403;
import static com.kohere.docs.DiagnosisDocsFields.DETAIL_404;
import static com.kohere.docs.DiagnosisDocsFields.DETAIL_DESCRIPTION;
import static com.kohere.docs.DiagnosisDocsFields.DETAIL_SUMMARY;
import static com.kohere.docs.DiagnosisDocsFields.HISTORY_400;
import static com.kohere.docs.DiagnosisDocsFields.HISTORY_401;
import static com.kohere.docs.DiagnosisDocsFields.HISTORY_DESCRIPTION;
import static com.kohere.docs.DiagnosisDocsFields.HISTORY_SUMMARY;
import static com.kohere.docs.DiagnosisDocsFields.LATEST_401;
import static com.kohere.docs.DiagnosisDocsFields.LATEST_DESCRIPTION;
import static com.kohere.docs.DiagnosisDocsFields.LATEST_SUMMARY;
import static com.kohere.docs.DiagnosisDocsFields.detailResponseFields;
import static com.kohere.docs.DiagnosisDocsFields.diagnosisIdPathParameters;
import static com.kohere.docs.DiagnosisDocsFields.historyQueryParameters;
import static com.kohere.docs.DiagnosisDocsFields.historyResponseFields;
import static com.kohere.docs.DiagnosisDocsFields.latestResponseFields;
import static com.kohere.docs.DocsTokens.bearer;
import static com.kohere.docs.DocsTokens.expiredAccessToken;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.post;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.restdocs.request.RequestDocumentation.pathParameters;
import static org.springframework.restdocs.request.RequestDocumentation.queryParameters;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kohere.TestcontainersConfiguration;
import com.kohere.common.response.PageInfo;
import com.kohere.common.response.PageResponse;
import com.kohere.common.security.JwtProperties;
import com.kohere.common.security.JwtTokenService;
import com.kohere.diagnosis.infrastructure.DiagnosisQuestionDocument.OptionSpec;
import com.kohere.diagnosis.infrastructure.DiagnosisQuestionDocument.SelectSpec;
import com.kohere.docs.ApiDocsTags;
import com.kohere.listing.api.ListingCodeLabelView;
import com.kohere.listing.api.ListingRecommendationService;
import com.kohere.listing.api.RecommendedListingView;
import com.kohere.user.api.UserAccountService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
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
import org.springframework.restdocs.request.ParameterDescriptor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 진단 조회 3종(이력·최근·단건 상세)의 Spring REST Docs 스니펫 생성 테스트(ADR-0007·0016). 성공 응답과 스펙
 * (02-diagnosis-recommendation.md §4~§6)에 정의된 에러 응답을 {@code build/generated-snippets}에 생성해 OpenAPI3
 * 명세(Swagger UI)에 합류시킨다.
 *
 * <p><b>읽는 대상은 서버 주도 흐름이 확정한 진단이다</b> — 픽스처도 {@code POST /api/v2/diagnoses/start} → {@code /next}
 * 6회로 만든다. 그래서 이 파일은 "조회 3종이 v2가 저장한 진단을 그대로 읽는다"는 계약까지 함께 고정한다.
 *
 * <p><b>문서 규약</b>(#151) — 오퍼레이션(path+method)당 summary·description 상수를 한 벌 만들고 그 오퍼레이션의 성공·에러 스니펫이
 * 전부 같은 문자열을 쓴다(생성기는 첫 non-blank 하나만 채택하고 순서는 파일 순회에 좌우된다). 태그는 {@link ApiDocsTags#DIAGNOSIS} 하나이며
 * 문구·필드 기술자는 {@link com.kohere.docs.DiagnosisDocsFields}가 정본이다.
 *
 * <p>cross-module 협력(listing 추천·user 표시 언어)은 {@code @MockitoBean}으로 대체하고 access 토큰은 {@link
 * JwtTokenService}로 직접 발급한다(test-strategy §4). MongoDB는 실제 컨테이너로, Security·JPA·Redis 컨텍스트는 실제로
 * 구동한다. 문항 시더는 {@code test} 프로파일에서 비활성이라 이 테스트가 직접 카탈로그를 시드한다.
 */
@SpringBootTest
@ExtendWith(RestDocumentationExtension.class)
@ActiveProfiles("test")
@Testcontainers
@Import(TestcontainersConfiguration.class)
class DiagnosisDocsTest {

  @Container @ServiceConnection static MongoDBContainer mongo = new MongoDBContainer("mongo:7.0");

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
  @Autowired private DiagnosisFlowSessionMongoRepository flowSessionMongoRepository;

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
    flowSessionMongoRepository.deleteAll();
    seedQuestions();
    // 표시 언어는 user 공개 query로 취득(ADR-0029) — users.lang='ko'인 사용자를 가정해 한국어 라벨을 내려받는다.
    given(userAccountService.getLanguage(anyLong())).willReturn("ko");
    // ① 지역 게이트가 부르는 1-arg 스텁. 미스텁이면 null 이 돌아와 게이트에서 NPE 가 나고,
    // 빈 페이지면 흐름이 regionRetry 로 빠져 진단이 확정되지 않는다.
    given(listingRecommendationService.recommendByCriteria(any())).willReturn(pageOf(sampleView()));
    given(listingRecommendationService.recommendByCriteria(any(), anyString()))
        .willAnswer(
            invocation ->
                listingRecommendationService.recommendByCriteria(invocation.getArgument(0)));
  }

  @Test
  void generatesDiagnosisQuerySnippets() throws Exception {
    String token = jwtTokenService.issueAccessToken(1L);
    long diagnosisId = createCompletedDiagnosis(token);

    // 확정 진단이 하나도 없는 사용자 — 최근 조회의 completed=false 분기를 그린다.
    String freshToken = jwtTokenService.issueAccessToken(2L);

    // ① 내 진단 이력 목록
    mockMvc
        .perform(
            get("/api/v1/diagnoses")
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .param("page", "0")
                .param("size", "20")
                .param("sort", "submittedAt,desc"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[0].diagnosisId").value(diagnosisId))
        .andExpect(jsonPath("$.data.content[0].status").value("COMPLETED"))
        .andDo(
            document(
                "diagnosis-history",
                resourceDetails()
                    .tag(ApiDocsTags.DIAGNOSIS)
                    .summary(HISTORY_SUMMARY)
                    .description(HISTORY_DESCRIPTION),
                queryParameters(historyQueryParameters()),
                responseFields(historyResponseFields())));

    // ② 최근 진단 단건(홈 완료 여부 분기)
    // latest 응답의 요약 필드는 completed=false 케이스 때문에 전부 optional로 문서화한다 —
    // REST Docs의 "선언 안 한 필드가 오면 실패" 방어선이 약해지는 만큼 값 단정으로 되메운다(#151 규약 13).
    mockMvc
        .perform(get("/api/v1/diagnoses/latest").header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.completed").value(true))
        .andExpect(jsonPath("$.data.diagnosisId").value(diagnosisId))
        .andExpect(jsonPath("$.data.region").value("SEOUL"))
        .andExpect(jsonPath("$.data.purpose").value("STUDY"))
        .andExpect(jsonPath("$.data.university").value("SNU_CAU_SOONGSIL"))
        .andExpect(jsonPath("$.data.district").isEmpty())
        .andExpect(jsonPath("$.data.conditions[0]").value("FEMALE_ONLY"))
        .andExpect(jsonPath("$.data.monthlyRentMin").value(200000))
        .andExpect(jsonPath("$.data.monthlyRentMax").value(500000))
        .andExpect(jsonPath("$.data.arcStatus").value("ARC_ISSUED"))
        .andExpect(jsonPath("$.data.submittedAt").isString())
        .andDo(
            document(
                "diagnosis-latest",
                resourceDetails()
                    .tag(ApiDocsTags.DIAGNOSIS)
                    .summary(LATEST_SUMMARY)
                    .description(LATEST_DESCRIPTION),
                responseFields(latestResponseFields())));

    // ③ 최근 진단 단건 — 확정 이력 없음. 홈이 "진단 시작"을 그리는 쪽 분기이며 404가 아니라 200이다.
    // completed=false여도 요약 10개 필드는 키가 사라지지 않고 값이 null이다(@JsonInclude 없음).
    // 그래서 이 케이스의 되메움 단정은 doesNotExist()가 아니라 isEmpty()다 — 키 부재면 isEmpty()가 실패한다(규약 13).
    mockMvc
        .perform(
            get("/api/v1/diagnoses/latest").header(HttpHeaders.AUTHORIZATION, bearer(freshToken)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.completed").value(false))
        .andExpect(jsonPath("$.data.diagnosisId").isEmpty())
        .andExpect(jsonPath("$.data.region").isEmpty())
        .andExpect(jsonPath("$.data.purpose").isEmpty())
        .andExpect(jsonPath("$.data.university").isEmpty())
        .andExpect(jsonPath("$.data.district").isEmpty())
        .andExpect(jsonPath("$.data.conditions").isEmpty())
        .andExpect(jsonPath("$.data.monthlyRentMin").isEmpty())
        .andExpect(jsonPath("$.data.monthlyRentMax").isEmpty())
        .andExpect(jsonPath("$.data.arcStatus").isEmpty())
        .andExpect(jsonPath("$.data.submittedAt").isEmpty())
        .andDo(
            document(
                "diagnosis-latest-not-completed",
                resourceDetails()
                    .tag(ApiDocsTags.DIAGNOSIS)
                    .summary(LATEST_SUMMARY)
                    .description(LATEST_DESCRIPTION),
                responseFields(latestResponseFields())));

    // ④ 진단 단건 상세(입력 다시 보기, 본인 소유 확정 진단만)
    mockMvc
        .perform(
            get("/api/v1/diagnoses/{diagnosisId}", diagnosisId)
                .header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.diagnosisId").value(diagnosisId))
        .andExpect(jsonPath("$.data.status").value("COMPLETED"))
        .andDo(
            document(
                "diagnosis-detail",
                resourceDetails()
                    .tag(ApiDocsTags.DIAGNOSIS)
                    .summary(DETAIL_SUMMARY)
                    .description(DETAIL_DESCRIPTION),
                pathParameters(diagnosisIdPathParameters()),
                responseFields(detailResponseFields())));
  }

  @Test
  void generatesDiagnosisQueryErrorSnippets() throws Exception {
    String ownerToken = jwtTokenService.issueAccessToken(100L);
    String strangerToken = jwtTokenService.issueAccessToken(101L);
    String expiredToken = expiredAccessToken(jwtProperties);

    long ownedId = createCompletedDiagnosis(ownerToken);
    long missingId = 9_999_999L;

    // ===== GET /diagnoses (이력) =====
    perform(
        get("/api/v1/diagnoses")
            .header(HttpHeaders.AUTHORIZATION, bearer(ownerToken))
            .param("sort", "unknownKey,desc"),
        status().isBadRequest(),
        "INVALID_INPUT",
        "diagnosis-history-invalid-input",
        HISTORY_SUMMARY,
        HISTORY_DESCRIPTION,
        HISTORY_400);

    perform(
        get("/api/v1/diagnoses").header(HttpHeaders.AUTHORIZATION, bearer(FORGED_TOKEN)),
        status().isUnauthorized(),
        "UNAUTHENTICATED",
        "diagnosis-history-unauthenticated",
        HISTORY_SUMMARY,
        HISTORY_DESCRIPTION,
        HISTORY_401);

    perform(
        get("/api/v1/diagnoses").header(HttpHeaders.AUTHORIZATION, bearer(expiredToken)),
        status().isUnauthorized(),
        "TOKEN_EXPIRED",
        "diagnosis-history-token-expired",
        HISTORY_SUMMARY,
        HISTORY_DESCRIPTION,
        HISTORY_401);

    // ===== GET /diagnoses/latest =====
    perform(
        get("/api/v1/diagnoses/latest").header(HttpHeaders.AUTHORIZATION, bearer(FORGED_TOKEN)),
        status().isUnauthorized(),
        "UNAUTHENTICATED",
        "diagnosis-latest-unauthenticated",
        LATEST_SUMMARY,
        LATEST_DESCRIPTION,
        LATEST_401);

    perform(
        get("/api/v1/diagnoses/latest").header(HttpHeaders.AUTHORIZATION, bearer(expiredToken)),
        status().isUnauthorized(),
        "TOKEN_EXPIRED",
        "diagnosis-latest-token-expired",
        LATEST_SUMMARY,
        LATEST_DESCRIPTION,
        LATEST_401);

    // ===== GET /diagnoses/{id} =====
    performWithPathParams(
        get("/api/v1/diagnoses/{diagnosisId}", ownedId)
            .header(HttpHeaders.AUTHORIZATION, bearer(strangerToken)),
        status().isForbidden(),
        "FORBIDDEN",
        "diagnosis-detail-forbidden",
        DETAIL_SUMMARY,
        DETAIL_DESCRIPTION,
        diagnosisIdPathParameters(),
        DETAIL_403);

    performWithPathParams(
        get("/api/v1/diagnoses/{diagnosisId}", missingId)
            .header(HttpHeaders.AUTHORIZATION, bearer(ownerToken)),
        status().isNotFound(),
        "DIAGNOSIS_NOT_FOUND",
        "diagnosis-detail-not-found",
        DETAIL_SUMMARY,
        DETAIL_DESCRIPTION,
        diagnosisIdPathParameters(),
        DETAIL_404);

    performWithPathParams(
        get("/api/v1/diagnoses/{diagnosisId}", ownedId)
            .header(HttpHeaders.AUTHORIZATION, bearer(FORGED_TOKEN)),
        status().isUnauthorized(),
        "UNAUTHENTICATED",
        "diagnosis-detail-unauthenticated",
        DETAIL_SUMMARY,
        DETAIL_DESCRIPTION,
        diagnosisIdPathParameters(),
        DETAIL_401);

    performWithPathParams(
        get("/api/v1/diagnoses/{diagnosisId}", ownedId)
            .header(HttpHeaders.AUTHORIZATION, bearer(expiredToken)),
        status().isUnauthorized(),
        "TOKEN_EXPIRED",
        "diagnosis-detail-token-expired",
        DETAIL_SUMMARY,
        DETAIL_DESCRIPTION,
        diagnosisIdPathParameters(),
        DETAIL_401);
  }

  // ---- helpers (flow) ----

  /**
   * 서버 주도 흐름으로 진단 하나를 확정하고 발급된 diagnosisId를 돌려준다(STUDY 흐름).
   *
   * <p>조회 3종의 픽스처를 v2로 만드는 것이 곧 "이 API들이 읽는 대상은 v2가 저장한 진단"이라는 계약의 검증이다.
   */
  private long createCompletedDiagnosis(String token) throws Exception {
    mockMvc
        .perform(post("/api/v2/diagnoses/start").header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isOk());
    next(token, answerJson("region", "SEOUL"));
    next(token, answerJson("purpose", "STUDY"));
    next(token, answerJson("university", "SNU_CAU_SOONGSIL"));
    next(token, "{\"field\":\"conditions\",\"codes\":[\"FEMALE_ONLY\"]}");
    next(token, answerRentJson(200000, 500000));
    String completed = next(token, answerJson("arcStatus", "ARC_ISSUED"));
    return Long.parseLong(read(completed, "data", "diagnosisId"));
  }

  private String next(String token, String body) throws Exception {
    return mockMvc
        .perform(
            post("/api/v2/diagnoses/next")
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

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
        .andDo(errorSnippet(identifier, ApiDocsTags.DIAGNOSIS, summary, description, errorCodes));
  }

  /** path 변수가 있는 오퍼레이션의 에러 스니펫 — 파라미터 설명이 스니펫 순서에 좌우되지 않도록 함께 선언한다(규약 12). */
  private void performWithPathParams(
      MockHttpServletRequestBuilder request,
      ResultMatcher expectedStatus,
      String expectedCode,
      String identifier,
      String summary,
      String description,
      ParameterDescriptor[] pathParameters,
      String... errorCodes)
      throws Exception {
    mockMvc
        .perform(request)
        .andExpect(expectedStatus)
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.error.code").value(expectedCode))
        .andDo(
            errorSnippet(
                identifier,
                ApiDocsTags.DIAGNOSIS,
                summary,
                description,
                pathParameters,
                errorCodes));
  }

  // ---- seed / fixtures ----

  /**
   * 서버 주도 흐름이 쓰는 문항 8건을 전부 시드한다. 6슬롯 중 하나라도 빠지면 그 슬롯 차례에 카탈로그 조회가 실패해 확정에 도달하지 못하고, {@code
   * regionRetry}가 없으면 ① 지역 0건 분기에서 같은 일이 벌어진다.
   */
  private void seedQuestions() {
    questionMongoRepository.save(
        question(
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
            "regionRetry",
            "SINGLE",
            1,
            Map.of(
                "en",
                "There are no listings in that region. Try another?",
                "ko",
                "현재 지역에는 매물이 없어요. 다른 지역 방을 찾아보시겠어요?"),
            List.of(option("YES", "Yes", "예"), option("NO", "No", "아니오"))));
    questionMongoRepository.save(
        question(
            "purpose",
            "SINGLE",
            1,
            Map.of("en", "Why are you coming to Korea?", "ko", "입국 목적이 무엇인가요?"),
            List.of(option("STUDY", "Study", "유학"), option("NON_STUDY", "Other", "그 외"))));
    questionMongoRepository.save(
        question(
            "university",
            "SINGLE",
            1,
            Map.of("en", "Which university group?", "ko", "어느 대학 그룹인가요?"),
            List.of(
                option("SNU_CAU_SOONGSIL", "Seoul National, Chung-Ang, Soongsil", "서울대·중앙대·숭실대"),
                option("ETC", "Other", "기타"))));
    questionMongoRepository.save(
        question(
            "district",
            "SINGLE",
            1,
            Map.of("en", "Which district?", "ko", "어느 지역구인가요?"),
            List.of(option("GURO_GU", "Guro-gu", "구로구"), option("ETC", "Other", "기타"))));
    questionMongoRepository.save(
        question(
            "conditions",
            "MULTI",
            3,
            Map.of("en", "What matters to you?", "ko", "어떤 조건이 중요한가요?"),
            List.of(
                option("FEMALE_ONLY", "Female only", "여성 전용"),
                option("PRIVATE_BATH", "Private bath", "개인 욕실"),
                option("ENGLISH_OK", "English OK", "영어 가능"))));
    questionMongoRepository.save(
        DiagnosisQuestionDocument.builder()
            .field("monthlyRent")
            .active(true)
            .question(Map.of("en", "Monthly rent range?", "ko", "월세 범위는 어떻게 되나요?"))
            .select(SelectSpec.builder().type("NUMBER_RANGE").max(0).build())
            .options(List.of())
            .build());
    questionMongoRepository.save(
        question(
            "arcStatus",
            "SINGLE",
            1,
            Map.of("en", "Do you have an ARC?", "ko", "외국인등록증을 발급받으셨나요?"),
            List.of(
                option("ARC_ISSUED", "Issued", "발급 완료"), option("NO_ARC", "Not issued", "미발급"))));
  }

  private static DiagnosisQuestionDocument question(
      String field,
      String selectType,
      int max,
      Map<String, String> question,
      List<OptionSpec> options) {
    return DiagnosisQuestionDocument.builder()
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

  private static RecommendedListingView sampleView() {
    return new RecommendedListingView(
        "6858e2000000000000000001",
        "Sinchon Co-living House A",
        new ListingCodeLabelView("CO_LIVING", "Co-living"),
        550000,
        700000,
        1_000_000,
        1_500_000,
        "https://cdn.kohere.app/listings/5001/thumb.jpg",
        37.555134,
        126.936893,
        new RecommendedListingView.NearestTransitView(
            new ListingCodeLabelView("BUS", "Bus"), "Stub Stop", 1),
        List.of(new ListingCodeLabelView("FEMALE_ONLY", "Female Only")));
  }

  private static PageResponse<RecommendedListingView> pageOf(RecommendedListingView... views) {
    List<RecommendedListingView> content = List.of(views);
    return PageResponse.of(content, new PageInfo(0, 20, content.size(), 1, false));
  }

  private String read(String json, String... path) throws Exception {
    JsonNode node = objectMapper.readTree(json);
    for (String key : path) {
      node = node.get(key);
    }
    return node.asText();
  }

  private static String answerJson(String field, String code) {
    return "{\"field\":\"" + field + "\",\"code\":\"" + code + "\"}";
  }

  private static String answerRentJson(int min, int max) {
    return "{\"field\":\"monthlyRent\",\"min\":" + min + ",\"max\":" + max + "}";
  }
}
