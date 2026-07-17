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
import com.kohere.listing.api.ListingRecommendationService;
import com.kohere.listing.api.RecommendedListingView;
import com.kohere.user.api.UserAccountService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
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
 * v2 서버 주도 진단 흐름({@code /api/v2/diagnoses/*})의 Spring REST Docs 스니펫 생성 테스트(issue #157·ADR-0036).
 * 시작·답 적용·확정·추천 조회의 성공 응답과 스펙(02-diagnosis-recommendation.md §v2)에 정의된 에러 응답을 {@code
 * build/generated-snippets}에 생성해 OpenAPI3 명세(Swagger UI)에 합류시킨다. v1 스니펫은 {@link DiagnosisDocsTest}가
 * 그대로 담당한다 — 두 버전이 공존하므로 문서도 분리한다.
 *
 * <p><b>흐름 응답은 태그드 유니온</b>이라 {@code resultCode}별로 스니펫을 따로 낸다 — {@code NEXT_QUESTION}(질문 채움)·{@code
 * COMPLETED}({@code diagnosisId}만)·{@code RESTART}·{@code TERMINATED}(코드만). 채워지지 않는 payload는 {@code
 * NON_NULL}로 생략되므로 각 스니펫은 실제로 실린 필드만 문서화한다.
 *
 * <p>주도권 경계도 문서에 남긴다 — 확정({@code COMPLETED})은 매물을 싣지 않고 {@code diagnosisId}만 주며, 매칭 0건은 클라이언트가 요청한
 * 추천 응답의 {@code resultCode=NO_MATCH}로만 드러난다(v1과 달리 조정 제안 {@code suggestions}이 없다).
 *
 * <p>cross-module 협력(listing 추천·user 표시 언어)은 {@code @MockitoBean}으로 대체하고 access 토큰은 {@link
 * JwtTokenService}로 직접 발급한다(test-strategy §4, {@link DiagnosisDocsTest}와 동일 전략). MongoDB(문항
 * 카탈로그·진단·진행 세션)는 실제 컨테이너로 구동한다. Mongock 시더는 {@code test} 프로파일에서 비활성이라 이 테스트가 직접 카탈로그를 시드한다 — v2는
 * 6슬롯 문항 전부와 ① 지역 0건 예외질문({@code regionRetry})까지 필요하다.
 */
@SpringBootTest
@ExtendWith(RestDocumentationExtension.class)
@ActiveProfiles("test")
@Testcontainers
@Import(TestcontainersConfiguration.class)
class DiagnosisV2DocsTest {

  @Container @ServiceConnection static MongoDBContainer mongo = new MongoDBContainer("mongo:7.0");

  private static final String MALFORMED_BODY = "{ \"oops\" }";

  // 서명이 깨진(다른 키로 서명) access 토큰. 401 UNAUTHENTICATED 를 유발하면서도 구조상 JWT 라, restdocs-api-spec 이
  // 무인증 예시에서도 bearerAuthJWT 보안 스킴을 도출하게 한다(비결정적 스니펫 병합 순서와 무관하게 Swagger 자물쇠 유지 —
  // DiagnosisDocsTest 와 동일 처리).
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
    // 표시 언어는 user 공개 query로 취득(ADR-0029) — 등록 국가(KR→ko)를 가정해 한국어 라벨을 내려받는다.
    given(userAccountService.getLanguage(anyLong())).willReturn("ko");
    // 기본은 "그 지역에 매물이 있음" — ① 지역 조기 게이트를 통과시킨다. 0건 경로를 보는 테스트가 개별로 덮어쓴다.
    given(listingRecommendationService.recommendByCriteria(any())).willReturn(pageOf(SAMPLE_VIEW));
  }

  /** v2-1 시작 → v2-2 정본 6슬롯 진행 → 자동 확정 → v2-3 추천 조회(MATCHED·NO_MATCH)까지의 성공 경로. */
  @Test
  void generatesV2FlowSnippets() throws Exception {
    long userId = 1L;
    String token = jwtTokenService.issueAccessToken(userId);

    // v2-1. POST /start — 진행 중 세션이 있어도 버리고 처음부터. 언제나 ① 지역 질문이다(본문 없음).
    mockMvc
        .perform(post("/api/v2/diagnoses/start").header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.resultCode").value("NEXT_QUESTION"))
        .andExpect(jsonPath("$.data.question.field").value("region"))
        .andDo(
            document(
                "diagnosis-v2-start",
                resourceDetails()
                    .summary("v2 진단 시작 — 진행 중 세션을 버리고 처음부터, ① 지역 질문 반환(본문 없음, 시작 시점은 클라이언트가 결정)"),
                responseFields(flowQuestionFields("① 지역 질문(step 1, field=region)"))));

    // v2-2. POST /next — ① 지역 답 적용 → 그 지역에 매물이 있으므로 정본 순서상 다음 슬롯(② 목적) 질문.
    mockMvc
        .perform(
            post("/api/v2/diagnoses/next")
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(answerJson("region", "SEOUL")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.resultCode").value("NEXT_QUESTION"))
        .andExpect(jsonPath("$.data.question.field").value("purpose"))
        .andDo(
            document(
                "diagnosis-v2-next-question",
                resourceDetails()
                    .summary(
                        "v2 답 적용 — 현재 문항 답 1개를 적용하고 서버가 정한 다음 질문을 NEXT_QUESTION으로 반환"
                            + "(클라이언트는 step을 지정하지 않는다)"),
                requestFields(nextRequestFields()),
                responseFields(flowQuestionFields("서버가 정한 다음 문항(여기서는 ② 입국 목적)"))));

    // ③ 대학/지역 분기는 서버가 저장된 purpose로 택일한다 — STUDY면 university 문항이 나온다(클라 분기 아님).
    next(token, answerJson("purpose", "STUDY"))
        .andExpect(jsonPath("$.data.question.field").value("university"));
    next(token, answerJson("university", "SNU_CAU_SOONGSIL"))
        .andExpect(jsonPath("$.data.question.field").value("conditions"));
    // ④ 주거 조건은 codes 배열로 답한다. 그 응답이 ⑤ 월세 문항인데, 6슬롯 중 이것만
    // select.type=NUMBER_RANGE·options 빈 배열이다 — "질문은 곧 선택지 목록"이라는 가정에서 의도적으로
    // 분리된 예외라(US-2-5), 클라가 피커가 아니라 숫자 입력 2개를 그려야 하는 유일한 문항이다.
    // 다른 스니펫의 문항이 전부 SINGLE+선택지라 이 모양을 예시로 남기지 않으면 명세 어디에도 없다.
    mockMvc
        .perform(
            post("/api/v2/diagnoses/next")
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"field\":\"conditions\",\"codes\":[\"FEMALE_ONLY\",\"PRIVATE_BATH\"]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.resultCode").value("NEXT_QUESTION"))
        .andExpect(jsonPath("$.data.question.field").value("monthlyRent"))
        .andExpect(jsonPath("$.data.question.select.type").value("NUMBER_RANGE"))
        .andExpect(jsonPath("$.data.question.options").isEmpty())
        .andDo(
            document(
                "diagnosis-v2-next-conditions",
                resourceDetails()
                    .summary(
                        "v2 답 적용 — ④ 주거 조건은 codes 배열로 전송(최대 3개). 응답인 ⑤ 월세 문항은"
                            + " select.type=NUMBER_RANGE·options 빈 배열 — 선택지가 아니라 숫자 2개를 입력받는다"),
                requestFields(nextRequestFields()),
                responseFields(flowRangeQuestionFields("⑤ 월세 범위 질문(step 5, field=monthlyRent)"))));

    // ⑤ 월세 범위만 답의 형태가 다르다 — code/codes가 아니라 min·max 두 숫자다(select.type=NUMBER_RANGE).
    // 다른 스니펫이 모두 code 형태라 예시를 따로 남기지 않으면 Swagger 요청 예시에 이 형태가 아예 없다.
    mockMvc
        .perform(
            post("/api/v2/diagnoses/next")
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(answerRentJson(300000, 600000)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.resultCode").value("NEXT_QUESTION"))
        .andExpect(jsonPath("$.data.question.field").value("arcStatus"))
        .andDo(
            document(
                "diagnosis-v2-next-monthly-rent",
                resourceDetails()
                    .summary(
                        "v2 답 적용 — ⑤ 월세 범위(monthlyRent)는 code가 아니라 min·max 두 숫자로 보낸다"
                            + "(KRW 정수, 각 0 이상·min ≤ max)"),
                requestFields(nextRequestFields()),
                responseFields(flowQuestionFields("서버가 정한 다음 문항(여기서는 ⑥ ARC 발급 상태)"))));

    // v2-2. 마지막 슬롯(⑥ ARC)을 답하면 빌더 완성 → 서버가 자동 확정하고 diagnosisId만 준다.
    // 매칭 유무는 확인하지 않는다 — 추천은 클라이언트가 시점을 정해 v2-3으로 별도 조회한다.
    String completed =
        mockMvc
            .perform(
                post("/api/v2/diagnoses/next")
                    .header(HttpHeaders.AUTHORIZATION, bearer(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(answerJson("arcStatus", "ARC_ISSUED")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.resultCode").value("COMPLETED"))
            .andExpect(jsonPath("$.data.question").doesNotExist())
            .andDo(
                document(
                    "diagnosis-v2-next-completed",
                    resourceDetails()
                        .summary(
                            "v2 답 적용 — 마지막 슬롯(⑥ arcStatus)을 답하면 자동 확정(COMPLETED). "
                                + "추천 매물은 싣지 않고 diagnosisId만 준다(매칭 유무도 확인하지 않는다 — 조회 시점은 클라이언트가 결정)"),
                    requestFields(nextRequestFields()),
                    responseFields(flowCompletedFields())))
            .andReturn()
            .getResponse()
            .getContentAsString();
    long diagnosisId = Long.parseLong(read(completed, "data", "diagnosisId"));

    // v2-3. GET /{id}/recommendations — 매칭 있음(MATCHED). 이 호출 자체가 "매물을 받겠다"는 클라이언트의 결정이다.
    mockMvc
        .perform(
            get("/api/v2/diagnoses/{diagnosisId}/recommendations", diagnosisId)
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .param("page", "0")
                .param("size", "20")
                .param("sort", "recommended,desc"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.resultCode").value("MATCHED"))
        // listing 뷰→응답 매핑은 같은 타입이 줄줄이 늘어선 위치 인자(문자열 4·금액 4·좌표 2)라 필드가 서로
        // 뒤바뀌어도 타입은 그대로다 — 스니펫이 곧 클라이언트 계약이므로 값까지 못 박아 자리바꿈을 잡는다.
        .andExpect(jsonPath("$.data.content[0].listingId").value("6858e2000000000000000001"))
        .andExpect(jsonPath("$.data.content[0].title").value("Sinchon Co-living House A"))
        .andExpect(jsonPath("$.data.content[0].type").value("CO_LIVING"))
        .andExpect(jsonPath("$.data.content[0].monthlyRentMin").value(550000))
        .andExpect(jsonPath("$.data.content[0].monthlyRentMax").value(700000))
        .andExpect(jsonPath("$.data.content[0].minDeposit").value(1_000_000))
        .andExpect(jsonPath("$.data.content[0].maxDeposit").value(1_500_000))
        .andExpect(
            jsonPath("$.data.content[0].thumbnailUrl")
                .value("https://cdn.kohere.app/listings/5001/thumb.jpg"))
        .andExpect(jsonPath("$.data.content[0].lat").value(37.555134))
        .andExpect(jsonPath("$.data.content[0].lng").value(126.936893))
        .andExpect(jsonPath("$.data.content[0].conditions[0]").value("FEMALE_ONLY"))
        .andExpect(jsonPath("$.data.content[0].conditions[1]").value("PRIVATE_BATH"))
        .andExpect(jsonPath("$.data.markers[0].listingId").value("6858e2000000000000000001"))
        .andExpect(jsonPath("$.data.markers[0].lat").value(37.555134))
        .andExpect(jsonPath("$.data.markers[0].lng").value(126.936893))
        .andDo(
            document(
                "diagnosis-v2-recommendations",
                resourceDetails()
                    .summary("v2 진단 결과 추천 — 매물 요약 + 지도 마커(MATCHED). 조회 시점·페이지·정렬은 클라이언트가 정한다"),
                pathParameters(
                    parameterWithName("diagnosisId").description("COMPLETED로 받은 확정 진단 식별자(본인 소유)")),
                queryParameters(recommendationQueryParameters()),
                responseFields(v2RecommendationFields())));

    // v2-3. 매칭 0건(NO_MATCH) — 에러가 아니라 정상 결과이며, v1 §7과 달리 조정 제안(suggestions)이 없다.
    given(listingRecommendationService.recommendByCriteria(any())).willReturn(emptyPage());
    mockMvc
        .perform(
            get("/api/v2/diagnoses/{diagnosisId}/recommendations", diagnosisId)
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .param("page", "0")
                .param("size", "20")
                .param("sort", "recommended,desc"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.resultCode").value("NO_MATCH"))
        .andExpect(jsonPath("$.data.content").isEmpty())
        .andExpect(jsonPath("$.data.suggestions").doesNotExist())
        .andDo(
            document(
                "diagnosis-v2-recommendations-no-match",
                resourceDetails()
                    .summary(
                        "v2 진단 결과 추천 — 0건(NO_MATCH): 빈 목록. 에러가 아니며 조정 제안(suggestions) 없이 사유만 결과코드로 준다"),
                pathParameters(
                    parameterWithName("diagnosisId").description("COMPLETED로 받은 확정 진단 식별자(본인 소유)")),
                queryParameters(recommendationQueryParameters()),
                responseFields(v2RecommendationNoMatchFields())));
  }

  /**
   * ① 지역 0건 예외질문(서버가 미리 필터링하는 유일한 지점)과 그 예/아니오 응답의 흐름 제어 코드.
   *
   * <p>예외질문 자체는 별도 결과코드가 아니라 카탈로그의 <b>일반 질문</b>({@code field=regionRetry})으로 내려가고, 그 응답에만 클라이언트가 행할
   * 행위를 코드로 알린다(예={@code RESTART} · 아니오={@code TERMINATED}).
   */
  @Test
  void generatesV2RegionRetrySnippets() throws Exception {
    String retryToken = jwtTokenService.issueAccessToken(10L);
    String endToken = jwtTokenService.issueAccessToken(11L);
    // 그 지역에 매물이 하나도 없는 상황 — ① 지역 답 직후 서버가 조기에 걸러낸다.
    given(listingRecommendationService.recommendByCriteria(any())).willReturn(emptyPage());

    start(retryToken);
    mockMvc
        .perform(
            post("/api/v2/diagnoses/next")
                .header(HttpHeaders.AUTHORIZATION, bearer(retryToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(answerJson("region", "BUSAN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.resultCode").value("NEXT_QUESTION"))
        .andExpect(jsonPath("$.data.question.field").value("regionRetry"))
        .andDo(
            document(
                "diagnosis-v2-next-region-retry",
                resourceDetails()
                    .summary(
                        "v2 답 적용 — ① 지역 답 직후 매물 0건이면 서버가 \"다른 지역?\" 예외질문을 끼워 넣는다"
                            + "(별도 코드가 아닌 일반 NEXT_QUESTION, field=regionRetry — 서버가 미리 필터링하는 유일한 지점)"),
                requestFields(nextRequestFields()),
                responseFields(
                    flowQuestionFields("① 지역 0건 예외질문(step 1, field=regionRetry, 예/아니오)"))));

    // 예 → 클라이언트가 POST /start로 처음부터 재시도한다(세션 삭제, 코드만).
    mockMvc
        .perform(
            post("/api/v2/diagnoses/next")
                .header(HttpHeaders.AUTHORIZATION, bearer(retryToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(answerJson("regionRetry", "YES")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.resultCode").value("RESTART"))
        .andExpect(jsonPath("$.data.diagnosisId").doesNotExist())
        .andDo(
            document(
                "diagnosis-v2-next-restart",
                resourceDetails()
                    .summary(
                        "v2 답 적용 — 지역 예외질문 \"예\"(YES): RESTART. 클라이언트가 POST /start로 처음부터 재시도한다(세션 삭제)"),
                requestFields(nextRequestFields()),
                responseFields(
                    flowCodeOnlyFields("RESTART — 클라이언트가 POST /api/v2/diagnoses/start로 재시도"))));

    // 아니오 → 진단 종료. 에러가 아니라 정상 결과라 error가 아닌 resultCode로 표현한다.
    start(endToken);
    next(endToken, answerJson("region", "BUSAN"))
        .andExpect(jsonPath("$.data.question.field").value("regionRetry"));
    mockMvc
        .perform(
            post("/api/v2/diagnoses/next")
                .header(HttpHeaders.AUTHORIZATION, bearer(endToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(answerJson("regionRetry", "NO")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.resultCode").value("TERMINATED"))
        .andExpect(jsonPath("$.data.question").doesNotExist())
        .andDo(
            document(
                "diagnosis-v2-next-terminated",
                resourceDetails()
                    .summary(
                        "v2 답 적용 — 지역 예외질문 \"아니오\"(NO): TERMINATED(진단 종료, 세션 삭제). "
                            + "에러가 아니라 정상 결과이므로 error가 아닌 resultCode로 표현한다"),
                requestFields(nextRequestFields()),
                responseFields(flowCodeOnlyFields("TERMINATED — 진단 종료"))));
  }

  /** 스펙 §v2의 "발생 가능한 에러"를 엔드포인트별로 실제 트리거해 스니펫으로 생성하고 status·error.code를 단정한다. */
  @Test
  void generatesV2ErrorSnippets() throws Exception {
    long owner = 100L;
    long stranger = 101L;
    String ownerToken = jwtTokenService.issueAccessToken(owner);
    String strangerToken = jwtTokenService.issueAccessToken(stranger);
    String freshToken = jwtTokenService.issueAccessToken(102L); // 진행 중 세션이 없는 사용자
    String expiredToken = expiredAccessToken();

    long ownedId = createCompletedDiagnosis(ownerToken);
    long missingId = 9_999_999L;

    // ===== POST /api/v2/diagnoses/start =====
    perform(
        post("/api/v2/diagnoses/start").header(HttpHeaders.AUTHORIZATION, bearer(FORGED_TOKEN)),
        status().isUnauthorized(),
        "UNAUTHENTICATED",
        "diagnosis-v2-start-unauthenticated",
        "v2 진단 시작 — 인증 누락/위조 (401 UNAUTHENTICATED)");

    perform(
        post("/api/v2/diagnoses/start").header(HttpHeaders.AUTHORIZATION, bearer(expiredToken)),
        status().isUnauthorized(),
        "TOKEN_EXPIRED",
        "diagnosis-v2-start-token-expired",
        "v2 진단 시작 — 액세스 토큰 만료 (401 TOKEN_EXPIRED)");

    // ===== POST /api/v2/diagnoses/next =====
    // 진행 중 세션 없이 /next — 서버가 임의로 흐름을 되살리지 않는다. 클라이언트가 /start로 복구한다.
    perform(
        post("/api/v2/diagnoses/next")
            .header(HttpHeaders.AUTHORIZATION, bearer(freshToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content(answerJson("region", "SEOUL")),
        status().isBadRequest(),
        "DIAGNOSIS_SESSION_NOT_FOUND",
        "diagnosis-v2-next-session-not-found",
        "v2 답 적용 — 진행 중 세션 없이 /next(앱 재시작·터미널 이후 재전송·만료) (400 DIAGNOSIS_SESSION_NOT_FOUND) "
            + "→ 클라이언트가 POST /api/v2/diagnoses/start로 복구한다");

    // 현재 문항(pendingField=region)이 아닌 답 — 정본 슬롯 문항과 예외질문이 같은 규칙으로 검증된다.
    start(ownerToken);
    perform(
        post("/api/v2/diagnoses/next")
            .header(HttpHeaders.AUTHORIZATION, bearer(ownerToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content(answerJson("arcStatus", "ARC_ISSUED")),
        status().isBadRequest(),
        "INVALID_INPUT",
        "diagnosis-v2-next-invalid-input",
        "v2 답 적용 — 답(field) 없음·현재 문항과 다른 field·미정의 enum·regionRetry가 YES/NO 아님 (400 INVALID_INPUT)");

    perform(
        post("/api/v2/diagnoses/next")
            .header(HttpHeaders.AUTHORIZATION, bearer(ownerToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content(MALFORMED_BODY),
        status().isBadRequest(),
        "MALFORMED_REQUEST",
        "diagnosis-v2-next-malformed",
        "v2 답 적용 — 본문 해석 불가 (400 MALFORMED_REQUEST)");

    perform(
        post("/api/v2/diagnoses/next")
            .header(HttpHeaders.AUTHORIZATION, bearer(FORGED_TOKEN))
            .contentType(MediaType.APPLICATION_JSON)
            .content(answerJson("region", "SEOUL")),
        status().isUnauthorized(),
        "UNAUTHENTICATED",
        "diagnosis-v2-next-unauthenticated",
        "v2 답 적용 — 인증 누락/위조 (401 UNAUTHENTICATED)");

    perform(
        post("/api/v2/diagnoses/next")
            .header(HttpHeaders.AUTHORIZATION, bearer(expiredToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content(answerJson("region", "SEOUL")),
        status().isUnauthorized(),
        "TOKEN_EXPIRED",
        "diagnosis-v2-next-token-expired",
        "v2 답 적용 — 액세스 토큰 만료 (401 TOKEN_EXPIRED)");

    // ===== GET /api/v2/diagnoses/{id}/recommendations =====
    perform(
        get("/api/v2/diagnoses/{diagnosisId}/recommendations", ownedId)
            .header(HttpHeaders.AUTHORIZATION, bearer(strangerToken)),
        status().isForbidden(),
        "FORBIDDEN",
        "diagnosis-v2-recommendations-forbidden",
        "v2 진단 결과 추천 — 타인 소유 진단 접근 (403 FORBIDDEN)");

    perform(
        get("/api/v2/diagnoses/{diagnosisId}/recommendations", missingId)
            .header(HttpHeaders.AUTHORIZATION, bearer(ownerToken)),
        status().isNotFound(),
        "DIAGNOSIS_NOT_FOUND",
        "diagnosis-v2-recommendations-not-found",
        "v2 진단 결과 추천 — 진단이 존재하지 않음 (404 DIAGNOSIS_NOT_FOUND)");

    perform(
        get("/api/v2/diagnoses/{diagnosisId}/recommendations", ownedId)
            .header(HttpHeaders.AUTHORIZATION, bearer(ownerToken))
            .param("sort", "unknownKey,desc"),
        status().isBadRequest(),
        "INVALID_INPUT",
        "diagnosis-v2-recommendations-invalid-input",
        "v2 진단 결과 추천 — page/size 범위 위반·허용되지 않은 sort 키 또는 방향 (400 INVALID_INPUT)");

    perform(
        get("/api/v2/diagnoses/{diagnosisId}/recommendations", ownedId)
            .header(HttpHeaders.AUTHORIZATION, bearer(FORGED_TOKEN)),
        status().isUnauthorized(),
        "UNAUTHENTICATED",
        "diagnosis-v2-recommendations-unauthenticated",
        "v2 진단 결과 추천 — 인증 누락/위조 (401 UNAUTHENTICATED)");

    perform(
        get("/api/v2/diagnoses/{diagnosisId}/recommendations", ownedId)
            .header(HttpHeaders.AUTHORIZATION, bearer(expiredToken)),
        status().isUnauthorized(),
        "TOKEN_EXPIRED",
        "diagnosis-v2-recommendations-token-expired",
        "v2 진단 결과 추천 — 액세스 토큰 만료 (401 TOKEN_EXPIRED)");
  }

  // ---- helpers (flow) ----

  private void start(String token) throws Exception {
    mockMvc
        .perform(post("/api/v2/diagnoses/start").header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isOk());
  }

  private org.springframework.test.web.servlet.ResultActions next(String token, String body)
      throws Exception {
    return mockMvc
        .perform(
            post("/api/v2/diagnoses/next")
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());
  }

  /** v2 흐름만으로 한 사용자의 진단을 확정까지 진행하고 발급된 diagnosisId를 돌려준다(STUDY 흐름). */
  private long createCompletedDiagnosis(String token) throws Exception {
    start(token);
    next(token, answerJson("region", "SEOUL"));
    next(token, answerJson("purpose", "STUDY"));
    next(token, answerJson("university", "SNU_CAU_SOONGSIL"));
    next(token, "{\"field\":\"conditions\",\"codes\":[\"FEMALE_ONLY\"]}");
    next(token, answerRentJson(200000, 500000));
    String completed =
        next(token, answerJson("arcStatus", "ARC_ISSUED"))
            .andExpect(jsonPath("$.data.resultCode").value("COMPLETED"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return Long.parseLong(read(completed, "data", "diagnosisId"));
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

  /** POST /next 요청 바디 — v1 §2 AnswerRequest와 동일 구조(해당하는 쪽만 채우고 나머지는 보내지 않는다). */
  private static List<FieldDescriptor> nextRequestFields() {
    return List.of(
        field(
            "field",
            JsonFieldType.STRING,
            "현재 문항의 제출 필드명 — 서버가 직전에 낸 문항과 같아야 한다(다르면 INVALID_INPUT)"),
        optField(
            "code",
            JsonFieldType.STRING,
            "단일 선택 답의 코드(enum 이름). ① 지역 0건 예외질문(regionRetry)은 YES 또는 NO"),
        optField("codes", JsonFieldType.ARRAY, "다중 선택 답의 코드 집합(④ conditions, 최대 3개·중복 불가)"),
        optField("min", JsonFieldType.NUMBER, "월세 범위(⑤ monthlyRent) 하한(KRW 정수)"),
        optField("max", JsonFieldType.NUMBER, "월세 범위(⑤ monthlyRent) 상한(KRW 정수)"));
  }

  /** resultCode=NEXT_QUESTION — question payload가 채워지고 diagnosisId는 생략된다. */
  private static List<FieldDescriptor> flowQuestionFields(String questionDescription) {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"),
        field(
            "data.resultCode",
            JsonFieldType.STRING,
            "결과코드(NEXT_QUESTION) — 다음 질문이 남았다. ① 지역 0건 예외질문도 이 코드로 내려간다"),
        field("data.question.step", JsonFieldType.NUMBER, "질문 단계(1~6). " + questionDescription),
        field(
            "data.question.field",
            JsonFieldType.STRING,
            "제출 필드명 — 다음 /next 요청의 field에 그대로 실어 보낸다"
                + "(region·purpose·university·district·conditions·monthlyRent·arcStatus·regionRetry)"),
        field("data.question.question", JsonFieldType.STRING, "등록 국가 언어로 번역된 질문 라벨(미지원 언어는 영어 폴백)"),
        field(
            "data.question.select.type", JsonFieldType.STRING, "선택 방식(SINGLE|MULTI|NUMBER_RANGE)"),
        field("data.question.select.max", JsonFieldType.NUMBER, "최대 선택 개수(MULTI는 3)"),
        field("data.question.options[].code", JsonFieldType.STRING, "선택지 코드(enum, 언어 무관 동일)"),
        field("data.question.options[].label", JsonFieldType.STRING, "번역된 표시 라벨"),
        errorNull());
  }

  /**
   * resultCode=NEXT_QUESTION — 선택지가 없는 ⑤ 월세 문항({@code NUMBER_RANGE}). {@link #flowQuestionFields}와
   * {@code options}만 다르다 — 그쪽은 {@code options[]}의 원소를 서술하는데 이 문항은 배열이 비어 있어 원소가 없다(원소를 서술하면
   * "payload에 없는 필드"로 실패한다).
   */
  private static List<FieldDescriptor> flowRangeQuestionFields(String questionDescription) {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"),
        field("data.resultCode", JsonFieldType.STRING, "결과코드(NEXT_QUESTION) — 다음 질문이 남았다"),
        field("data.question.step", JsonFieldType.NUMBER, "질문 단계(1~6). " + questionDescription),
        field(
            "data.question.field", JsonFieldType.STRING, "제출 필드명 — 다음 /next 요청의 field에 그대로 실어 보낸다"),
        field("data.question.question", JsonFieldType.STRING, "등록 국가 언어로 번역된 질문 라벨(미지원 언어는 영어 폴백)"),
        field(
            "data.question.select.type",
            JsonFieldType.STRING,
            "선택 방식 — NUMBER_RANGE는 고정 선택지가 아니라 min·max 두 숫자를 입력받는다는 뜻이다"),
        field("data.question.select.max", JsonFieldType.NUMBER, "최대 선택 개수(NUMBER_RANGE는 무의미 — 1)"),
        field(
            "data.question.options",
            JsonFieldType.ARRAY,
            "선택지 목록 — NUMBER_RANGE 문항은 고정 선택지가 없어 항상 빈 배열이다."
                + " 클라이언트는 선택지 피커가 아니라 숫자 입력 2개(min·max)를 그리고, 답은 {field, min, max}로 보낸다"),
        errorNull());
  }

  /** resultCode=COMPLETED — diagnosisId만 채워지고 question은 생략된다(추천 매물 없음). */
  private static List<FieldDescriptor> flowCompletedFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"),
        field(
            "data.resultCode",
            JsonFieldType.STRING,
            "결과코드(COMPLETED) — 빌더 완성으로 서버가 자동 확정했다(매칭 유무와 무관)"),
        field(
            "data.diagnosisId",
            JsonFieldType.NUMBER,
            "확정된 진단 식별자 — 클라이언트가 이 id로 GET /api/v2/diagnoses/{diagnosisId}/recommendations를 별도 호출한다"),
        errorNull());
  }

  /** resultCode=RESTART|TERMINATED — 코드만 실리고 question·diagnosisId는 둘 다 생략된다. */
  private static List<FieldDescriptor> flowCodeOnlyFields(String resultCodeDescription) {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"),
        field("data.resultCode", JsonFieldType.STRING, "결과코드 — " + resultCodeDescription),
        errorNull());
  }

  private static List<FieldDescriptor> v2RecommendationFields() {
    List<FieldDescriptor> fields = new ArrayList<>();
    fields.add(field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"));
    fields.add(field("data.resultCode", JsonFieldType.STRING, "매칭 결과(MATCHED) — 조건에 맞는 매물이 있다"));
    fields.add(field("data.content[].listingId", JsonFieldType.STRING, "매물 식별자(ObjectId hex 문자열)"));
    fields.add(field("data.content[].title", JsonFieldType.STRING, "매물 제목"));
    fields.add(field("data.content[].type", JsonFieldType.STRING, "주거 유형(원시 문자열)"));
    fields.add(field("data.content[].monthlyRentMin", JsonFieldType.NUMBER, "월세 범위 하한(KRW)"));
    fields.add(field("data.content[].monthlyRentMax", JsonFieldType.NUMBER, "월세 범위 상한(KRW)"));
    fields.add(field("data.content[].minDeposit", JsonFieldType.NUMBER, "보증금 범위 하한(KRW)"));
    fields.add(field("data.content[].maxDeposit", JsonFieldType.NUMBER, "보증금 범위 상한(KRW)"));
    fields.add(optField("data.content[].thumbnailUrl", JsonFieldType.STRING, "썸네일 URL(없으면 null)"));
    fields.add(field("data.content[].lat", JsonFieldType.NUMBER, "위도(WGS84)"));
    fields.add(field("data.content[].lng", JsonFieldType.NUMBER, "경도(WGS84)"));
    fields.add(
        field(
            "data.content[].conditions",
            JsonFieldType.ARRAY,
            "추천 카드 조건 배지 목록. listing 모듈이 ACTIVE 방 타입들의 filterTags 합집합에 NO_ARC 같은 매물 정책 조건을 더해 내려주는 원시 문자열 코드"));
    fields.add(
        field("data.markers[].listingId", JsonFieldType.STRING, "마커 매물 식별자(ObjectId hex 문자열)"));
    fields.add(field("data.markers[].lat", JsonFieldType.NUMBER, "마커 위도"));
    fields.add(field("data.markers[].lng", JsonFieldType.NUMBER, "마커 경도"));
    fields.addAll(pageFields(""));
    fields.add(errorNull());
    return fields;
  }

  private static List<FieldDescriptor> v2RecommendationNoMatchFields() {
    List<FieldDescriptor> fields = new ArrayList<>();
    fields.add(field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"));
    fields.add(
        field(
            "data.resultCode",
            JsonFieldType.STRING,
            "매칭 결과(NO_MATCH) — 조건에 맞는 매물이 0건. 에러가 아니며 v1과 달리 조정 제안(suggestions)이 없다"));
    fields.add(field("data.content", JsonFieldType.ARRAY, "추천 매물 목록(0건이면 빈 배열)"));
    fields.add(field("data.markers", JsonFieldType.ARRAY, "지도 마커(0건이면 빈 배열)"));
    fields.addAll(pageFields("(0)"));
    fields.add(errorNull());
    return fields;
  }

  private static List<FieldDescriptor> pageFields(String zeroNote) {
    return List.of(
        field("data.page.number", JsonFieldType.NUMBER, "현재 페이지 번호(0-base)"),
        field("data.page.size", JsonFieldType.NUMBER, "페이지 크기"),
        field("data.page.totalElements", JsonFieldType.NUMBER, "전체 건수" + zeroNote),
        field("data.page.totalPages", JsonFieldType.NUMBER, "전체 페이지 수" + zeroNote),
        field("data.page.hasNext", JsonFieldType.BOOLEAN, "다음 페이지 존재 여부"));
  }

  // ---- query parameter descriptors (varargs — RequestDocumentation has no List overload) ----

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

  /**
   * v2 흐름이 지나는 문항 전부를 시드한다 — 정본 6슬롯(③은 university·district 2건)과 ① 지역 0건 예외질문(regionRetry). 흐름이 자동으로
   * 다음 문항을 내므로 하나라도 빠지면 카탈로그 조회에서 깨진다(v1 스니펫 테스트는 클라가 지정한 step만 조회해 일부만 시드해도 됐다).
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
                    "There are no rooms in this region yet. Would you like to look in another region?",
                "ko", "현재 지역에는 매물이 없어요. 다른 지역 방을 찾아보시겠어요?"),
            List.of(option("YES", "Yes", "예"), option("NO", "No", "아니오"))));
    questionMongoRepository.save(
        question(
            "purpose",
            "SINGLE",
            1,
            Map.of("en", "What is your purpose of stay?", "ko", "입국 목적이 무엇인가요?"),
            List.of(option("STUDY", "Study", "유학(학업)"), option("NON_STUDY", "Other", "그 외"))));
    questionMongoRepository.save(
        question(
            "university",
            "SINGLE",
            1,
            Map.of("en", "Which university do you attend?", "ko", "어느 대학교에 다니나요?"),
            List.of(
                option("SNU_CAU_SOONGSIL", "Seoul National · Chung-Ang · Soongsil", "서울대·중앙대·숭실대"),
                option("HUFS_KHU_KOREA", "HUFS · Kyung Hee · Korea Univ.", "한국외대·경희대·고려대"))));
    questionMongoRepository.save(
        question(
            "district",
            "SINGLE",
            1,
            Map.of("en", "Which district will you live in?", "ko", "어느 지역(구)에서 거주할 예정인가요?"),
            List.of(option("GURO_GU", "Guro-gu", "구로구"), option("GWANAK_GU", "Gwanak-gu", "관악구"))));
    questionMongoRepository.save(
        question(
            "conditions",
            "MULTI",
            3,
            Map.of(
                "en", "Select your housing conditions (up to 3).",
                "ko", "원하는 주거 조건을 선택하세요(최대 3개)."),
            List.of(
                option("FEMALE_ONLY", "Female only", "여성 전용"),
                option("PRIVATE_BATH", "Private bath", "개인 욕실"),
                option("ENGLISH_OK", "English available", "영어 가능"))));
    questionMongoRepository.save(
        question(
            "monthlyRent",
            "NUMBER_RANGE",
            1,
            Map.of(
                "en", "What is your monthly rent range (min–max)? (KRW)",
                "ko", "월세 범위(최소~최대)는 얼마인가요?(원)"),
            List.of()));
    questionMongoRepository.save(
        question(
            "arcStatus",
            "SINGLE",
            1,
            Map.of("en", "What is your ARC issuance status?", "ko", "외국인등록증(ARC) 발급 상태는 어떤가요?"),
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

  private static final RecommendedListingView SAMPLE_VIEW =
      new RecommendedListingView(
          "6858e2000000000000000001",
          "Sinchon Co-living House A",
          "CO_LIVING",
          550000,
          700000,
          1_000_000,
          1_500_000,
          "https://cdn.kohere.app/listings/5001/thumb.jpg",
          37.555134,
          126.936893,
          List.of("FEMALE_ONLY", "PRIVATE_BATH"));

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

  private static String answerRentJson(int min, int max) {
    return "{\"field\":\"monthlyRent\",\"min\":" + min + ",\"max\":" + max + "}";
  }
}
