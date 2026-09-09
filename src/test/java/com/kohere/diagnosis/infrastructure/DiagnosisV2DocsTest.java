package com.kohere.diagnosis.infrastructure;

import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.document;
import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.resourceDetails;
import static com.kohere.docs.ApiDocsErrors.errorSnippet;
import static com.kohere.docs.DiagnosisDocsFields.GUEST_SESSION_HEADER;
import static com.kohere.docs.DiagnosisDocsFields.V2_NEXT_400;
import static com.kohere.docs.DiagnosisDocsFields.V2_NEXT_401;
import static com.kohere.docs.DiagnosisDocsFields.V2_NEXT_DESCRIPTION;
import static com.kohere.docs.DiagnosisDocsFields.V2_NEXT_SUMMARY;
import static com.kohere.docs.DiagnosisDocsFields.V2_RECOMMENDATIONS_400;
import static com.kohere.docs.DiagnosisDocsFields.V2_RECOMMENDATIONS_401;
import static com.kohere.docs.DiagnosisDocsFields.V2_RECOMMENDATIONS_403;
import static com.kohere.docs.DiagnosisDocsFields.V2_RECOMMENDATIONS_404;
import static com.kohere.docs.DiagnosisDocsFields.V2_RECOMMENDATIONS_DESCRIPTION;
import static com.kohere.docs.DiagnosisDocsFields.V2_RECOMMENDATIONS_SUMMARY;
import static com.kohere.docs.DiagnosisDocsFields.V2_RECOMMENDATION_MAP_401;
import static com.kohere.docs.DiagnosisDocsFields.V2_RECOMMENDATION_MAP_403;
import static com.kohere.docs.DiagnosisDocsFields.V2_RECOMMENDATION_MAP_404;
import static com.kohere.docs.DiagnosisDocsFields.V2_RECOMMENDATION_MAP_DESCRIPTION;
import static com.kohere.docs.DiagnosisDocsFields.V2_RECOMMENDATION_MAP_SUMMARY;
import static com.kohere.docs.DiagnosisDocsFields.V2_START_401;
import static com.kohere.docs.DiagnosisDocsFields.V2_START_DESCRIPTION;
import static com.kohere.docs.DiagnosisDocsFields.V2_START_SUMMARY;
import static com.kohere.docs.DiagnosisDocsFields.guestSessionHeader;
import static com.kohere.docs.DiagnosisDocsFields.nextRequestFields;
import static com.kohere.docs.DiagnosisDocsFields.nextResponseFields;
import static com.kohere.docs.DiagnosisDocsFields.recommendationQueryParameters;
import static com.kohere.docs.DiagnosisDocsFields.startResponseFields;
import static com.kohere.docs.DiagnosisDocsFields.v2DiagnosisIdPathParameters;
import static com.kohere.docs.DiagnosisDocsFields.v2RecommendationFields;
import static com.kohere.docs.DiagnosisDocsFields.v2RecommendationMapFields;
import static com.kohere.docs.DocsTokens.bearer;
import static com.kohere.docs.DocsTokens.expiredAccessToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.restdocs.headers.HeaderDocumentation.requestHeaders;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.post;
import static org.springframework.restdocs.payload.PayloadDocumentation.requestFields;
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
import com.kohere.listing.api.RecommendedListingMarkersView;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
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
 * 담당한다.
 *
 * <p><b>문서 규약</b>(#151) — 이 파일이 문서화하는 오퍼레이션은 셋({@code POST /start} · {@code POST /next} · {@code
 * GET /{id}/recommendations})뿐이고, {@code resultCode}·회원/게스트별 스니펫은 전부 그 셋 중 하나로 병합된다. 그래서
 * summary·description은 <b>오퍼레이션당 한 벌</b>만 두고 케이스 구분은 document identifier(= Swagger Examples 드롭다운
 * 항목명)로 한다. 태그도 v1과 같은 {@link ApiDocsTags#DIAGNOSIS} 하나다 — 나누면 공유 오퍼레이션이 두 그룹에 중복 노출된다.
 *
 * <p><b>흐름 응답은 태그드 유니온</b>이라 {@code resultCode}별로 스니펫을 따로 내되({@code NEXT_QUESTION}·{@code
 * COMPLETED}·{@code RESTART}·{@code TERMINATED}) <b>필드 기술자는 한 벌로 합친다</b> — 같은 {@code (path, method,
 * status)}의 기술자는 어차피 {@code (path, type)} 기준으로 하나로 접히므로, 여러 벌을 두면 승자가 파일 순회 순서에 좌우된다. 채워지지 않는
 * payload는 {@code NON_NULL}로 생략되므로 그 필드들은 {@code optional}이고, 각 스니펫이 {@code doesNotExist()}로 「이
 * 케이스에는 없다」를 못 박는다.
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
    // 표시 언어는 user 공개 query로 취득(ADR-0029) — users.lang='ko'인 회원을 가정해 한국어 라벨을 내려받는다.
    given(userAccountService.getLanguage(anyLong())).willReturn("ko");
    // 기본은 "그 지역에 매물이 있음" — ① 지역 조기 게이트를 통과시킨다. 0건 경로를 보는 테스트가 개별로 덮어쓴다.
    given(listingRecommendationService.recommendByCriteria(any())).willReturn(pageOf(SAMPLE_VIEW));
    // 마커 포트 기본 스텁. 없으면 Mockito 기본값 null이 돌아와 NPE(500)가 나는데,
    // 그러면 스니펫이 캡처되지 않아 오퍼레이션이 Swagger에서 통째로 사라진다(빌드는 통과한다).
    given(listingRecommendationService.recommendMarkersByCriteria(any()))
        .willReturn(
            new RecommendedListingMarkersView(
                List.of(
                    new RecommendedListingMarkersView.Marker(
                        "6858e2000000000000000001", 37.555134, 126.936893)),
                1L));
    given(listingRecommendationService.recommendByCriteria(any(), anyString()))
        .willAnswer(
            invocation ->
                listingRecommendationService.recommendByCriteria(invocation.getArgument(0)));
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
        .andExpect(jsonPath("$.data.question.step").value(1))
        .andExpect(jsonPath("$.data.question.select.type").value("SINGLE"))
        .andExpect(jsonPath("$.data.question.options[0].code").value("SEOUL"))
        // 회원 응답에는 게스트 세션 키가 실리지 않는다(NON_NULL — 값 null이 아니라 필드 자체가 없다).
        .andExpect(jsonPath("$.data.guestSessionId").doesNotExist())
        .andDo(
            document(
                "diagnosis-v2-start",
                resourceDetails()
                    .tag(ApiDocsTags.DIAGNOSIS)
                    .summary(V2_START_SUMMARY)
                    .description(V2_START_DESCRIPTION),
                responseFields(startResponseFields())));

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
        .andExpect(jsonPath("$.data.question.step").value(2))
        .andExpect(jsonPath("$.data.question.options[0].code").value("STUDY"))
        .andExpect(jsonPath("$.data.diagnosisId").doesNotExist())
        .andDo(
            document(
                "diagnosis-v2-next-question",
                resourceDetails()
                    .tag(ApiDocsTags.DIAGNOSIS)
                    .summary(V2_NEXT_SUMMARY)
                    .description(V2_NEXT_DESCRIPTION),
                requestFields(nextRequestFields()),
                responseFields(nextResponseFields())));

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
        .andExpect(jsonPath("$.data.question.step").value(5))
        .andExpect(jsonPath("$.data.question.select.type").value("NUMBER_RANGE"))
        .andExpect(jsonPath("$.data.question.options").isEmpty())
        .andExpect(jsonPath("$.data.question.options[0]").doesNotExist())
        .andExpect(jsonPath("$.data.diagnosisId").doesNotExist())
        .andDo(
            document(
                "diagnosis-v2-next-conditions",
                resourceDetails()
                    .tag(ApiDocsTags.DIAGNOSIS)
                    .summary(V2_NEXT_SUMMARY)
                    .description(V2_NEXT_DESCRIPTION),
                requestFields(nextRequestFields()),
                responseFields(nextResponseFields())));

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
        .andExpect(jsonPath("$.data.question.step").value(6))
        .andExpect(jsonPath("$.data.question.options[0].code").value("ARC_ISSUED"))
        .andExpect(jsonPath("$.data.diagnosisId").doesNotExist())
        .andDo(
            document(
                "diagnosis-v2-next-monthly-rent",
                resourceDetails()
                    .tag(ApiDocsTags.DIAGNOSIS)
                    .summary(V2_NEXT_SUMMARY)
                    .description(V2_NEXT_DESCRIPTION),
                requestFields(nextRequestFields()),
                responseFields(nextResponseFields())));

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
                        .tag(ApiDocsTags.DIAGNOSIS)
                        .summary(V2_NEXT_SUMMARY)
                        .description(V2_NEXT_DESCRIPTION),
                    requestFields(nextRequestFields()),
                    responseFields(nextResponseFields())))
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
        // 0건 스니펫과 필드 기술자를 공유하느라 원소가 optional이라 이 단정이 계약을 되메운다(#151 규약 13).
        .andExpect(jsonPath("$.data.content[0].listingId").value("6858e2000000000000000001"))
        .andExpect(jsonPath("$.data.content[0].title").value("Sinchon Co-living House A"))
        .andExpect(jsonPath("$.data.content[0].type.code").value("CO_LIVING"))
        .andExpect(jsonPath("$.data.content[0].type.label").value("Co-living"))
        .andExpect(jsonPath("$.data.content[0].monthlyRentMin").value(550000))
        .andExpect(jsonPath("$.data.content[0].monthlyRentMax").value(700000))
        .andExpect(jsonPath("$.data.content[0].minDeposit").value(1_000_000))
        .andExpect(jsonPath("$.data.content[0].maxDeposit").value(1_500_000))
        .andExpect(
            jsonPath("$.data.content[0].thumbnailUrl")
                .value("https://cdn.kohere.app/listings/5001/thumb.jpg"))
        .andExpect(jsonPath("$.data.content[0].lat").value(37.555134))
        .andExpect(jsonPath("$.data.content[0].lng").value(126.936893))
        .andExpect(jsonPath("$.data.content[0].conditions[0].code").value("FEMALE_ONLY"))
        .andExpect(jsonPath("$.data.content[0].conditions[0].label").value("Female Only"))
        .andExpect(jsonPath("$.data.content[0].conditions[1].code").value("PRIVATE_BATH"))
        .andExpect(jsonPath("$.data.markers[0].listingId").value("6858e2000000000000000001"))
        .andExpect(jsonPath("$.data.markers[0].lat").value(37.555134))
        .andExpect(jsonPath("$.data.markers[0].lng").value(126.936893))
        .andDo(
            document(
                "diagnosis-v2-recommendations",
                resourceDetails()
                    .tag(ApiDocsTags.DIAGNOSIS)
                    .summary(V2_RECOMMENDATIONS_SUMMARY)
                    .description(V2_RECOMMENDATIONS_DESCRIPTION),
                pathParameters(v2DiagnosisIdPathParameters()),
                queryParameters(recommendationQueryParameters()),
                responseFields(v2RecommendationFields())));

    // v2-3. 매칭 0건(NO_MATCH) — 에러가 아니라 정상 결과다.
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
        // 0건이면 카드·마커 원소가 통째로 없다(값이 null인 것이 아니다) — 규약 13.
        .andExpect(jsonPath("$.data.content[0]").doesNotExist())
        .andExpect(jsonPath("$.data.markers[0]").doesNotExist())
        .andExpect(jsonPath("$.data.suggestions").doesNotExist())
        .andDo(
            document(
                "diagnosis-v2-recommendations-no-match",
                resourceDetails()
                    .tag(ApiDocsTags.DIAGNOSIS)
                    .summary(V2_RECOMMENDATIONS_SUMMARY)
                    .description(V2_RECOMMENDATIONS_DESCRIPTION),
                pathParameters(v2DiagnosisIdPathParameters()),
                queryParameters(recommendationQueryParameters()),
                responseFields(v2RecommendationFields())));
  }

  /**
   * 지도 화면이 쓰는 마커 전용 조회. 페이지 조회와 <b>같은 매칭 조건</b>이되 좌표만 내려간다.
   *
   * <p>매칭 있음과 0건 두 스니펫이 <b>같은 필드 헬퍼 하나</b>를 쓴다 — 같은 {@code (path, 200)}에 기술자를 두 벌 두면 스키마가 둘 생기고 파일
   * 순회 순서에 따라 하나가 조용히 버려진다.
   */
  @Test
  void generatesV2RecommendationMapSnippets() throws Exception {
    long userId = 40L;
    String token = jwtTokenService.issueAccessToken(userId);
    long diagnosisId = createCompletedDiagnosis(token);

    given(listingRecommendationService.recommendMarkersByCriteria(any()))
        .willReturn(
            new RecommendedListingMarkersView(
                List.of(
                    new RecommendedListingMarkersView.Marker(
                        "6858e2000000000000000001", 37.555134, 126.936893),
                    new RecommendedListingMarkersView.Marker(
                        "6858e2000000000000000002", 37.497942, 127.027621)),
                137L));

    mockMvc
        .perform(
            get("/api/v2/diagnoses/{diagnosisId}/recommendations/map", diagnosisId)
                .header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isOk())
        // 좌표 축이 뒤바뀌어도 둘 다 double이라 타입으로는 안 잡힌다 — 값으로 못 박는다.
        .andExpect(jsonPath("$.data.markers[0].listingId").value("6858e2000000000000000001"))
        .andExpect(jsonPath("$.data.markers[0].lat").value(37.555134))
        .andExpect(jsonPath("$.data.markers[0].lng").value(126.936893))
        // total이 markers 길이보다 크면 상한에 걸려 잘린 것이다.
        .andExpect(jsonPath("$.data.total").value(137))
        .andDo(
            document(
                "diagnosis-v2-recommendations-map",
                resourceDetails()
                    .tag(ApiDocsTags.DIAGNOSIS)
                    .summary(V2_RECOMMENDATION_MAP_SUMMARY)
                    .description(V2_RECOMMENDATION_MAP_DESCRIPTION),
                pathParameters(v2DiagnosisIdPathParameters()),
                responseFields(v2RecommendationMapFields())));

    given(listingRecommendationService.recommendMarkersByCriteria(any()))
        .willReturn(new RecommendedListingMarkersView(List.of(), 0L));

    mockMvc
        .perform(
            get("/api/v2/diagnoses/{diagnosisId}/recommendations/map", diagnosisId)
                .header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(0))
        // 0건이면 원소가 통째로 없다(값이 null인 것이 아니다) — 원소 기술자를 optional로 낮춘 대가를 되메운다.
        .andExpect(jsonPath("$.data.markers").isEmpty())
        .andExpect(jsonPath("$.data.markers[0]").doesNotExist())
        .andDo(
            document(
                "diagnosis-v2-recommendations-map-empty",
                resourceDetails()
                    .tag(ApiDocsTags.DIAGNOSIS)
                    .summary(V2_RECOMMENDATION_MAP_SUMMARY)
                    .description(V2_RECOMMENDATION_MAP_DESCRIPTION),
                pathParameters(v2DiagnosisIdPathParameters()),
                responseFields(v2RecommendationMapFields())));
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
        .andExpect(jsonPath("$.data.question.step").value(1))
        .andExpect(jsonPath("$.data.question.options[0].code").value("YES"))
        .andExpect(jsonPath("$.data.diagnosisId").doesNotExist())
        .andDo(
            document(
                "diagnosis-v2-next-region-retry",
                resourceDetails()
                    .tag(ApiDocsTags.DIAGNOSIS)
                    .summary(V2_NEXT_SUMMARY)
                    .description(V2_NEXT_DESCRIPTION),
                requestFields(nextRequestFields()),
                responseFields(nextResponseFields())));

    // 예 → 클라이언트가 POST /start로 처음부터 재시도한다(세션 삭제, 코드만).
    mockMvc
        .perform(
            post("/api/v2/diagnoses/next")
                .header(HttpHeaders.AUTHORIZATION, bearer(retryToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(answerJson("regionRetry", "YES")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.resultCode").value("RESTART"))
        .andExpect(jsonPath("$.data.question").doesNotExist())
        .andExpect(jsonPath("$.data.diagnosisId").doesNotExist())
        .andDo(
            document(
                "diagnosis-v2-next-restart",
                resourceDetails()
                    .tag(ApiDocsTags.DIAGNOSIS)
                    .summary(V2_NEXT_SUMMARY)
                    .description(V2_NEXT_DESCRIPTION),
                requestFields(nextRequestFields()),
                responseFields(nextResponseFields())));

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
        .andExpect(jsonPath("$.data.diagnosisId").doesNotExist())
        .andDo(
            document(
                "diagnosis-v2-next-terminated",
                resourceDetails()
                    .tag(ApiDocsTags.DIAGNOSIS)
                    .summary(V2_NEXT_SUMMARY)
                    .description(V2_NEXT_DESCRIPTION),
                requestFields(nextRequestFields()),
                responseFields(nextResponseFields())));
  }

  /**
   * 게스트 흐름 스니펫(#181) — 회원 스니펫({@link #generatesV2FlowSnippets})과 같은 오퍼레이션이라 restdocs-api-spec이 예시를
   * 함께 병합한다. 게스트 예시가 있어야 Swagger에 <b>{@code /start} 응답의 {@code guestSessionId}</b>와 <b>{@code
   * /next}·추천의 {@code X-Guest-Session-Id} 요청 헤더</b>가 드러난다(회원 시점만 문서화하면 비회원 사용법이 명세에 없다).
   */
  @Test
  void generatesV2GuestSnippets() throws Exception {
    given(listingRecommendationService.recommendByCriteria(any())).willReturn(pageOf(SAMPLE_VIEW));

    // ① POST /start (토큰 없음) — 게스트 세션 키를 발급해 응답에 싣는다. 회원 응답에는 없는 필드다(NON_NULL).
    String startBody =
        mockMvc
            .perform(post("/api/v2/diagnoses/start"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.resultCode").value("NEXT_QUESTION"))
            .andExpect(jsonPath("$.data.question.field").value("region"))
            .andExpect(jsonPath("$.data.question.step").value(1))
            .andExpect(jsonPath("$.data.question.select.type").value("SINGLE"))
            .andExpect(jsonPath("$.data.question.options[0].code").value("SEOUL"))
            .andExpect(jsonPath("$.data.guestSessionId").exists())
            .andDo(
                document(
                    "diagnosis-v2-start-guest",
                    resourceDetails()
                        .tag(ApiDocsTags.DIAGNOSIS)
                        .summary(V2_START_SUMMARY)
                        .description(V2_START_DESCRIPTION),
                    responseFields(startResponseFields())))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String guestKey = read(startBody, "data", "guestSessionId");

    // ② POST /next (X-Guest-Session-Id 헤더) — 게스트는 이 헤더가 유일한 세션 조회 키다.
    mockMvc
        .perform(
            post("/api/v2/diagnoses/next")
                .header(GUEST_SESSION_HEADER, guestKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(answerJson("region", "SEOUL")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.question.field").value("purpose"))
        .andExpect(jsonPath("$.data.question.step").value(2))
        .andExpect(jsonPath("$.data.question.options[0].code").value("STUDY"))
        .andExpect(jsonPath("$.data.diagnosisId").doesNotExist())
        .andDo(
            document(
                "diagnosis-v2-next-guest",
                resourceDetails()
                    .tag(ApiDocsTags.DIAGNOSIS)
                    .summary(V2_NEXT_SUMMARY)
                    .description(V2_NEXT_DESCRIPTION),
                requestHeaders(guestSessionHeader()),
                requestFields(nextRequestFields()),
                responseFields(nextResponseFields())));

    // ③ 확정까지 진행 → GET /{id}/recommendations 도 헤더만으로 소유권을 증명한다(① 지역은 위에서 이미 답했다).
    long diagnosisId = completeGuestFlowAfterRegion(guestKey);
    mockMvc
        .perform(
            get("/api/v2/diagnoses/{diagnosisId}/recommendations", diagnosisId)
                .header(GUEST_SESSION_HEADER, guestKey)
                .param("page", "0")
                .param("size", "20")
                .param("sort", "recommended,desc"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.resultCode").value("MATCHED"))
        .andExpect(jsonPath("$.data.content[0].listingId").value("6858e2000000000000000001"))
        .andDo(
            document(
                "diagnosis-v2-recommendations-guest",
                resourceDetails()
                    .tag(ApiDocsTags.DIAGNOSIS)
                    .summary(V2_RECOMMENDATIONS_SUMMARY)
                    .description(V2_RECOMMENDATIONS_DESCRIPTION),
                requestHeaders(guestSessionHeader()),
                pathParameters(v2DiagnosisIdPathParameters()),
                queryParameters(recommendationQueryParameters()),
                responseFields(v2RecommendationFields())));

    // ④ 지도 마커도 같은 헤더만으로 소유권을 증명한다 — 게스트가 닿는 네 번째 엔드포인트다.
    given(listingRecommendationService.recommendMarkersByCriteria(any()))
        .willReturn(
            new RecommendedListingMarkersView(
                List.of(
                    new RecommendedListingMarkersView.Marker(
                        "6858e2000000000000000001", 37.555134, 126.936893)),
                1L));
    mockMvc
        .perform(
            get("/api/v2/diagnoses/{diagnosisId}/recommendations/map", diagnosisId)
                .header(GUEST_SESSION_HEADER, guestKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.markers[0].listingId").value("6858e2000000000000000001"))
        .andExpect(jsonPath("$.data.total").value(1))
        .andDo(
            document(
                "diagnosis-v2-recommendations-map-guest",
                resourceDetails()
                    .tag(ApiDocsTags.DIAGNOSIS)
                    .summary(V2_RECOMMENDATION_MAP_SUMMARY)
                    .description(V2_RECOMMENDATION_MAP_DESCRIPTION),
                requestHeaders(guestSessionHeader()),
                pathParameters(v2DiagnosisIdPathParameters()),
                responseFields(v2RecommendationMapFields())));
  }

  /** 스펙 §v2의 "발생 가능한 에러"를 엔드포인트별로 실제 트리거해 스니펫으로 생성하고 status·error.code를 단정한다. */
  @Test
  void generatesV2ErrorSnippets() throws Exception {
    long owner = 100L;
    long stranger = 101L;
    String ownerToken = jwtTokenService.issueAccessToken(owner);
    String strangerToken = jwtTokenService.issueAccessToken(stranger);
    String freshToken = jwtTokenService.issueAccessToken(102L); // 진행 중 세션이 없는 사용자
    String expiredToken = expiredAccessToken(jwtProperties);

    long ownedId = createCompletedDiagnosis(ownerToken);
    long missingId = 9_999_999L;

    // ===== POST /api/v2/diagnoses/start =====
    // #181로 v2가 permitAll이 되면서 401 UNAUTHENTICATED(누락·위조) 세 케이스(start·next·추천)는
    // 도달 불가가 됐다 — guestFlowWithoutToken이 그 자리를 2xx 계약으로 대신 고정한다.
    // 만료 토큰만 401로 남는다(게스트 강등 금지 — 결정 11).
    perform(
        post("/api/v2/diagnoses/start").header(HttpHeaders.AUTHORIZATION, bearer(expiredToken)),
        status().isUnauthorized(),
        "TOKEN_EXPIRED",
        "diagnosis-v2-start-token-expired",
        V2_START_SUMMARY,
        V2_START_DESCRIPTION,
        V2_START_401);

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
        V2_NEXT_SUMMARY,
        V2_NEXT_DESCRIPTION,
        V2_NEXT_400);

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
        V2_NEXT_SUMMARY,
        V2_NEXT_DESCRIPTION,
        V2_NEXT_400);

    // 본문을 뺄 수 없다 — DiagnosisV2Controller.next 의 @RequestBody(required = false) 때문에
    // 빈 본문은 예외 없이 request=null 로 들어가 다른 코드가 된다. 깨진 JSON 이 있어야 MALFORMED_REQUEST 다.
    perform(
        post("/api/v2/diagnoses/next")
            .header(HttpHeaders.AUTHORIZATION, bearer(ownerToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content(MALFORMED_BODY),
        status().isBadRequest(),
        "MALFORMED_REQUEST",
        "diagnosis-v2-next-malformed",
        V2_NEXT_SUMMARY,
        V2_NEXT_DESCRIPTION,
        V2_NEXT_400);

    // 401 은 시큐리티 필터(JwtAuthenticationFilter)가 DispatcherServlet 이전에 낸다 — 본문과 무관하므로
    // Swagger 요청 예시 중복을 없애려 본문을 싣지 않는다(#151-4).
    perform(
        post("/api/v2/diagnoses/next")
            .header(HttpHeaders.AUTHORIZATION, bearer(expiredToken))
            .contentType(MediaType.APPLICATION_JSON),
        status().isUnauthorized(),
        "TOKEN_EXPIRED",
        "diagnosis-v2-next-token-expired",
        V2_NEXT_SUMMARY,
        V2_NEXT_DESCRIPTION,
        V2_NEXT_401);

    // ===== GET /api/v2/diagnoses/{id}/recommendations =====
    performWithPathParams(
        get("/api/v2/diagnoses/{diagnosisId}/recommendations", ownedId)
            .header(HttpHeaders.AUTHORIZATION, bearer(strangerToken)),
        status().isForbidden(),
        "FORBIDDEN",
        "diagnosis-v2-recommendations-forbidden",
        V2_RECOMMENDATIONS_SUMMARY,
        V2_RECOMMENDATIONS_DESCRIPTION,
        v2DiagnosisIdPathParameters(),
        V2_RECOMMENDATIONS_403);

    performWithPathParams(
        get("/api/v2/diagnoses/{diagnosisId}/recommendations", missingId)
            .header(HttpHeaders.AUTHORIZATION, bearer(ownerToken)),
        status().isNotFound(),
        "DIAGNOSIS_NOT_FOUND",
        "diagnosis-v2-recommendations-not-found",
        V2_RECOMMENDATIONS_SUMMARY,
        V2_RECOMMENDATIONS_DESCRIPTION,
        v2DiagnosisIdPathParameters(),
        V2_RECOMMENDATIONS_404);

    performWithPathParams(
        get("/api/v2/diagnoses/{diagnosisId}/recommendations", ownedId)
            .header(HttpHeaders.AUTHORIZATION, bearer(ownerToken))
            .param("sort", "unknownKey,desc"),
        status().isBadRequest(),
        "INVALID_INPUT",
        "diagnosis-v2-recommendations-invalid-input",
        V2_RECOMMENDATIONS_SUMMARY,
        V2_RECOMMENDATIONS_DESCRIPTION,
        v2DiagnosisIdPathParameters(),
        V2_RECOMMENDATIONS_400);

    performWithPathParams(
        get("/api/v2/diagnoses/{diagnosisId}/recommendations", ownedId)
            .header(HttpHeaders.AUTHORIZATION, bearer(expiredToken)),
        status().isUnauthorized(),
        "TOKEN_EXPIRED",
        "diagnosis-v2-recommendations-token-expired",
        V2_RECOMMENDATIONS_SUMMARY,
        V2_RECOMMENDATIONS_DESCRIPTION,
        v2DiagnosisIdPathParameters(),
        V2_RECOMMENDATIONS_401);

    // ===== GET /{id}/recommendations/map =====
    performWithPathParams(
        get("/api/v2/diagnoses/{diagnosisId}/recommendations/map", ownedId)
            .header(GUEST_SESSION_HEADER, "anonymous-not-the-owner"),
        status().isForbidden(),
        "FORBIDDEN",
        "diagnosis-v2-recommendations-map-forbidden",
        V2_RECOMMENDATION_MAP_SUMMARY,
        V2_RECOMMENDATION_MAP_DESCRIPTION,
        v2DiagnosisIdPathParameters(),
        V2_RECOMMENDATION_MAP_403);

    performWithPathParams(
        get("/api/v2/diagnoses/{diagnosisId}/recommendations/map", 9_999_999L)
            .header(HttpHeaders.AUTHORIZATION, bearer(ownerToken)),
        status().isNotFound(),
        "DIAGNOSIS_NOT_FOUND",
        "diagnosis-v2-recommendations-map-not-found",
        V2_RECOMMENDATION_MAP_SUMMARY,
        V2_RECOMMENDATION_MAP_DESCRIPTION,
        v2DiagnosisIdPathParameters(),
        V2_RECOMMENDATION_MAP_404);

    performWithPathParams(
        get("/api/v2/diagnoses/{diagnosisId}/recommendations/map", ownedId)
            .header(HttpHeaders.AUTHORIZATION, bearer(expiredToken)),
        status().isUnauthorized(),
        "TOKEN_EXPIRED",
        "diagnosis-v2-recommendations-map-token-expired",
        V2_RECOMMENDATION_MAP_SUMMARY,
        V2_RECOMMENDATION_MAP_DESCRIPTION,
        v2DiagnosisIdPathParameters(),
        V2_RECOMMENDATION_MAP_401);
  }

  /**
   * 게이트 해제 계약(#181) — {@code Authorization} 헤더 <b>없이</b> v2 네 엔드포인트가 모두 2xx다.
   *
   * <p>표시 언어가 {@code en}인 것만 보면 부족하다 — {@code getLanguage}를 부르고 예외를 삼키는 구현도 통과한다. 게스트는 {@code
   * users} 행이 없어 호출 자체가 404이므로 <b>한 번도 부르지 않음</b>을 함께 못 박는다(회원 스텁은 ko라 en 응답이 곧 미호출의 방증이기도 하다).
   */
  @Test
  void guestFlowWithoutToken() throws Exception {
    given(listingRecommendationService.recommendByCriteria(any())).willReturn(pageOf(SAMPLE_VIEW));

    // ① /start — 키 발급 지점. 회원 응답에는 없는 guestSessionId가 실린다(NON_NULL).
    String startBody =
        mockMvc
            .perform(post("/api/v2/diagnoses/start"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.resultCode").value("NEXT_QUESTION"))
            .andExpect(jsonPath("$.data.question.field").value("region"))
            .andExpect(jsonPath("$.data.question.question").value("Which region will you live in?"))
            .andExpect(jsonPath("$.data.guestSessionId").exists())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String guestKey = read(startBody, "data", "guestSessionId");
    assertThat(guestKey).startsWith("anonymous");

    // ② /next — 헤더 에코로 세션이 이어진다.
    guestNext(guestKey, answerJson("region", "SEOUL"))
        .andExpect(jsonPath("$.data.question.field").value("purpose"));

    // ③ 확정까지 진행 → 추천 조회도 헤더만으로 통과한다(① 지역은 위에서 이미 답했다).
    long diagnosisId = completeGuestFlowAfterRegion(guestKey);
    mockMvc
        .perform(
            get("/api/v2/diagnoses/{diagnosisId}/recommendations", diagnosisId)
                .header(GUEST_SESSION_HEADER, guestKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.resultCode").value("MATCHED"))
        .andExpect(jsonPath("$.data.content[0].listingId").value("6858e2000000000000000001"));

    // ④ 지도 마커도 같은 헤더만으로 통과한다 — 게스트에게 열린 네 번째 엔드포인트다.
    mockMvc
        .perform(
            get("/api/v2/diagnoses/{diagnosisId}/recommendations/map", diagnosisId)
                .header(GUEST_SESSION_HEADER, guestKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.markers[0].listingId").value("6858e2000000000000000001"));

    verify(userAccountService, never()).getLanguage(anyLong());
  }

  /** 위조·형식 오류 토큰은 (만료와 달리) 게스트로 통과한다 — 신원 없는 요청과 같은 취급이다(#181). */
  @Test
  void forgedTokenIsTreatedAsGuest() throws Exception {
    mockMvc
        .perform(
            post("/api/v2/diagnoses/start").header(HttpHeaders.AUTHORIZATION, bearer(FORGED_TOKEN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.guestSessionId").exists())
        .andExpect(jsonPath("$.data.question.question").value("Which region will you live in?"));

    verify(userAccountService, never()).getLanguage(anyLong());
  }

  /**
   * 세션 연속성 — 게스트의 {@code /next}는 세션 키가 유일한 조회 키다. 키를 빠뜨렸거나 남의 키면 조회가 비어 돌아와 같은 코드({@code 400
   * DIAGNOSIS_SESSION_NOT_FOUND})가 되고, <b>남의 세션에는 닿지 않는다</b>.
   */
  @Test
  void guestSessionKeyIsRequiredAndScoped() throws Exception {
    given(listingRecommendationService.recommendByCriteria(any())).willReturn(pageOf(SAMPLE_VIEW));
    String keyA = guestStart();
    String keyB = guestStart();

    // 키 없이 /next → 400. 서버가 임의의 세션을 골라 주지 않는다.
    mockMvc
        .perform(
            post("/api/v2/diagnoses/next")
                .contentType(MediaType.APPLICATION_JSON)
                .content(answerJson("region", "SEOUL")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("DIAGNOSIS_SESSION_NOT_FOUND"));

    // A가 진행해도 B 세션은 그대로다 — 두 키가 서로의 세션을 건드리지 않는다.
    guestNext(keyA, answerJson("region", "SEOUL"))
        .andExpect(jsonPath("$.data.question.field").value("purpose"));
    guestNext(keyB, answerJson("region", "SEOUL"))
        .andExpect(jsonPath("$.data.question.field").value("purpose"));

    // 존재하지 않는 키 → 400(남의 세션으로 폴백하지 않는다).
    mockMvc
        .perform(
            post("/api/v2/diagnoses/next")
                .header(GUEST_SESSION_HEADER, "anonymous-does-not-exist")
                .contentType(MediaType.APPLICATION_JSON)
                .content(answerJson("purpose", "STUDY")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("DIAGNOSIS_SESSION_NOT_FOUND"));
  }

  /**
   * IDOR — 소유권은 신원 <b>종류</b>가 같고 값이 같을 때만 통과한다. 세 방향 모두 막힌다: 게스트 A↛게스트 B · 게스트↛회원 · 회원↛게스트. 진단 id가
   * 전역 순차 채번이라 열거가 쉬워 이 검사가 유일한 방어선이다.
   */
  @Test
  void recommendationsRejectCrossIdentityAccess() throws Exception {
    given(listingRecommendationService.recommendByCriteria(any())).willReturn(pageOf(SAMPLE_VIEW));
    String memberToken = jwtTokenService.issueAccessToken(500L);
    long memberDiagnosisId = createCompletedDiagnosis(memberToken);

    String keyA = guestStart();
    long guestDiagnosisId = completeGuestFlow(keyA);
    String keyB = guestStart();

    // 게스트 A ↛ 게스트 B
    mockMvc
        .perform(
            get("/api/v2/diagnoses/{diagnosisId}/recommendations", guestDiagnosisId)
                .header(GUEST_SESSION_HEADER, keyB))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

    // 게스트 ↛ 회원
    mockMvc
        .perform(
            get("/api/v2/diagnoses/{diagnosisId}/recommendations", memberDiagnosisId)
                .header(GUEST_SESSION_HEADER, keyA))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

    // 회원 ↛ 게스트 — 회원 요청은 헤더가 실려 와도 무시한다(키를 훔쳐도 통하지 않는다).
    mockMvc
        .perform(
            get("/api/v2/diagnoses/{diagnosisId}/recommendations", guestDiagnosisId)
                .header(HttpHeaders.AUTHORIZATION, bearer(memberToken))
                .header(GUEST_SESSION_HEADER, keyA))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

    // 신원 없는 요청(토큰도 키도 없음)은 어떤 진단도 소유하지 않는다.
    mockMvc
        .perform(get("/api/v2/diagnoses/{diagnosisId}/recommendations", guestDiagnosisId))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

    // 회원 무회귀 — 본인 진단은 그대로 조회된다.
    mockMvc
        .perform(
            get("/api/v2/diagnoses/{diagnosisId}/recommendations", memberDiagnosisId)
                .header(HttpHeaders.AUTHORIZATION, bearer(memberToken)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.resultCode").value("MATCHED"));
  }

  /**
   * 결정 3의 가드 — <b>v1 진단 조회는 게스트에게 열리지 않는다</b>. {@code permitAll} 매처는 {@code /api/v2/diagnoses/**}
   * 하나뿐이고 살아남은 조회 3종은 {@code anyRequest().authenticated()}에 남아 토큰이 필수다. v1에 매처가 조용히 추가되면 여기서 깨진다.
   */
  @Test
  void v1DiagnosesStayMemberOnly() throws Exception {
    mockMvc
        .perform(get("/api/v1/diagnoses"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));

    mockMvc
        .perform(post("/api/v1/diagnoses").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));

    mockMvc
        .perform(get("/api/v1/diagnoses/{diagnosisId}", 1L))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
  }

  /** 게스트 경로의 만료 토큰은 200이 아니라 401 {@code TOKEN_EXPIRED}다 — 조용한 게스트 강등을 막는다(결정 11). */
  @Test
  void expiredTokenIsNotDowngradedToGuest() throws Exception {
    mockMvc
        .perform(
            post("/api/v2/diagnoses/start")
                .header(HttpHeaders.AUTHORIZATION, bearer(expiredAccessToken(jwtProperties))))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("TOKEN_EXPIRED"))
        .andExpect(jsonPath("$.data").doesNotExist());
  }

  // ---- helpers (guest) ----

  /** 토큰 없이 {@code /start}를 호출해 발급된 게스트 세션 키를 돌려준다. */
  private String guestStart() throws Exception {
    String body =
        mockMvc
            .perform(post("/api/v2/diagnoses/start"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return read(body, "data", "guestSessionId");
  }

  private ResultActions guestNext(String guestKey, String body) throws Exception {
    return mockMvc
        .perform(
            post("/api/v2/diagnoses/next")
                .header(GUEST_SESSION_HEADER, guestKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());
  }

  /** 이미 {@code /start}한 게스트 세션을 ① 지역부터 끝까지 진행해 확정된 diagnosisId를 돌려준다(STUDY 흐름). */
  private long completeGuestFlow(String guestKey) throws Exception {
    guestNext(guestKey, answerJson("region", "SEOUL"));
    return completeGuestFlowAfterRegion(guestKey);
  }

  /**
   * ① 지역까지 답해 둔 게스트 세션을 ② 목적부터 끝까지 진행한다(지역을 두 번 답하면 INVALID_INPUT이다).
   *
   * <p>마지막 {@code /next}(⑥ {@code arcStatus})가 게스트 흐름의 확정 지점이라 여기서 {@code
   * diagnosis-v2-next-completed-guest} 스니펫을 낸다 — 회원 확정({@code diagnosis-v2-next-completed})만 문서화하면
   * 게스트가 {@code X-Guest-Session-Id}만으로 확정에 도달하는 모습이 명세에 없다.
   *
   * <p><b>이 헬퍼는 여러 테스트가 호출해 한 실행에서 스니펫이 여러 번 덮어써진다 — 무해하다.</b> 모든 호출부가 ① 지역(`SEOUL`)까지 답한 같은 상태로
   * 진입하고 헬퍼가 늘 같은 답을 같은 순서로 보내므로 요청·응답의 모양이 동일하다(달라지는 것은 {@code diagnosisId}와 게스트 세션 키 값뿐이다). 그래서
   * 스니펫을 밖으로 꺼내지 않는다.
   */
  private long completeGuestFlowAfterRegion(String guestKey) throws Exception {
    guestNext(guestKey, answerJson("purpose", "STUDY"));
    guestNext(guestKey, answerJson("university", "SNU_CAU_SOONGSIL"));
    guestNext(guestKey, "{\"field\":\"conditions\",\"codes\":[\"FEMALE_ONLY\"]}");
    guestNext(guestKey, answerRentJson(200000, 500000));
    String completed =
        guestNext(guestKey, answerJson("arcStatus", "ARC_ISSUED"))
            .andExpect(jsonPath("$.data.resultCode").value("COMPLETED"))
            .andExpect(jsonPath("$.data.question").doesNotExist())
            .andDo(
                document(
                    "diagnosis-v2-next-completed-guest",
                    resourceDetails()
                        .tag(ApiDocsTags.DIAGNOSIS)
                        .summary(V2_NEXT_SUMMARY)
                        .description(V2_NEXT_DESCRIPTION),
                    requestHeaders(guestSessionHeader()),
                    requestFields(nextRequestFields()),
                    responseFields(nextResponseFields())))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return Long.parseLong(read(completed, "data", "diagnosisId"));
  }

  // ---- helpers (flow) ----

  private void start(String token) throws Exception {
    mockMvc
        .perform(post("/api/v2/diagnoses/start").header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isOk());
  }

  private ResultActions next(String token, String body) throws Exception {
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
   * v2 흐름이 지나는 문항 전부를 시드한다 — 정본 6슬롯(③은 university·district 2건)과 ① 지역 0건 예외질문(regionRetry). 흐름이 자동으로
   * 다음 문항을 내므로 하나라도 빠지면 카탈로그 조회에서 깨진다.
   *
   * <p>선택지는 운영 카탈로그보다 짧은 축약 시드다 — 문서에는 실제 허용 코드를 스키마 enum으로 싣고 예시가 시드임을 description에 밝힌다.
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
          new ListingCodeLabelView("CO_LIVING", "Co-living"),
          550000,
          700000,
          1_000_000,
          1_500_000,
          "https://cdn.kohere.app/listings/5001/thumb.jpg",
          37.555134,
          126.936893,
          List.of(
              new ListingCodeLabelView("FEMALE_ONLY", "Female Only"),
              new ListingCodeLabelView("PRIVATE_BATH", "Private Bath")));

  private static PageResponse<RecommendedListingView> emptyPage() {
    return PageResponse.of(List.of(), new PageInfo(0, 20, 0L, 0, false));
  }

  private static PageResponse<RecommendedListingView> pageOf(RecommendedListingView... views) {
    List<RecommendedListingView> content = List.of(views);
    return PageResponse.of(content, new PageInfo(0, 20, content.size(), 1, false));
  }

  // ---- json helpers ----

  private String read(String json, String... path) throws Exception {
    JsonNode node = objectMapper.readTree(json);
    for (String key : path) {
      node = node.path(key);
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
