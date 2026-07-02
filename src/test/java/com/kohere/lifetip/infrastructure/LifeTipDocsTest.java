package com.kohere.lifetip.infrastructure;

import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.document;
import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.resourceDetails;
import static com.epages.restdocs.apispec.ResourceDocumentation.resource;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
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
import org.springframework.restdocs.RestDocumentationContextProvider;
import org.springframework.restdocs.RestDocumentationExtension;
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
 * 생활 팁 REST Docs 스니펫 생성 테스트(ADR-0007·0016). 성공(주제 목록·주제별 팁)과 스펙(08-life-tips.md) 에러(404·401·403)를
 * 실제 HTTP 스택으로 생성한다. 표시 언어(user getLanguage)는 {@code @MockitoBean}, MongoDB는 실제 컨테이너로 구동하며 시더는 test
 * 프로파일에서 비활성이라 이 테스트가 직접 시드한다.
 */
@SpringBootTest
@ExtendWith(RestDocumentationExtension.class)
@ActiveProfiles("test")
@Testcontainers
@Import(TestcontainersConfiguration.class)
class LifeTipDocsTest {

  @Container @ServiceConnection static MongoDBContainer mongo = new MongoDBContainer("mongo:7.0");

  // 서명이 깨진 토큰(401 UNAUTHENTICATED 유발). Bearer JWT 구조라 Swagger 보안 스킴이 유지된다(auth 문서화와 동일).
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
  @Autowired private LifeTipTopicMongoRepository topicRepository;
  @Autowired private LifeTipMongoRepository tipRepository;

  @MockitoBean private UserAccountService userAccountService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp(RestDocumentationContextProvider restDocumentation) {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .apply(springSecurity())
            .apply(documentationConfiguration(restDocumentation))
            .build();
    topicRepository.deleteAll();
    tipRepository.deleteAll();
    seed();
    // 표시 언어는 user 공개 query로 취득(ADR-0029) — 등록 국가(KR→ko)를 가정해 한국어 라벨을 내려받는다.
    given(userAccountService.getLanguage(anyLong())).willReturn("ko");
  }

  @Test
  void generatesLifeTipSnippets() throws Exception {
    String token = jwtTokenService.issueAccessToken(1L);

    mockMvc
        .perform(get("/api/v1/life-tips/topics").header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.topics[0].code").value("MOVING_IN"))
        .andExpect(jsonPath("$.data.topics[0].name").value("입주·이사"))
        .andDo(
            document(
                "life-tips-topics",
                resourceDetails().summary("생활 팁 주제 목록 — 노출 순서, 등록 국가 언어로 번역(비페이지, ROLE_USER)"),
                responseFields(topicsResponseFields())));

    mockMvc
        .perform(
            get("/api/v1/life-tips/topics/{topicCode}/tips", "MOVING_IN")
                .header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.tips[0].title").value("전입신고"))
        .andDo(
            document(
                "life-tips-topic-tips",
                resourceDetails().summary("특정 주제의 생활 팁(제목·내용·사진) — 노출 순서, 번역(비페이지, ROLE_USER)"),
                pathParameters(parameterWithName("topicCode").description("주제 코드(UPPER_SNAKE)")),
                responseFields(tipsResponseFields())));
  }

  @Test
  void generatesLifeTipErrorSnippets() throws Exception {
    String token = jwtTokenService.issueAccessToken(200L);
    String onboardingToken = jwtTokenService.issueOnboardingToken(201L);

    // 404 — 존재하지 않는 주제
    perform(
        get("/api/v1/life-tips/topics/{topicCode}/tips", "UNKNOWN_TOPIC")
            .header(HttpHeaders.AUTHORIZATION, bearer(token)),
        status().isNotFound(),
        "LIFE_TIP_TOPIC_NOT_FOUND",
        "life-tips-topic-tips-not-found",
        "주제별 팁 — 존재하지 않는 주제 코드 (404 LIFE_TIP_TOPIC_NOT_FOUND)");

    // 403 — 온보딩 미완료(ROLE_ONBOARDING) 토큰으로 ROLE_USER 자원 접근
    perform(
        get("/api/v1/life-tips/topics").header(HttpHeaders.AUTHORIZATION, bearer(onboardingToken)),
        status().isForbidden(),
        "AUTH_ONBOARDING_REQUIRED",
        "life-tips-topics-onboarding-required",
        "주제 목록 — 온보딩 미완료 토큰(정식 인증 ROLE_USER=ACTIVE만) (403 AUTH_ONBOARDING_REQUIRED)");

    // 401 — 인증 누락/위조
    perform(
        get("/api/v1/life-tips/topics").header(HttpHeaders.AUTHORIZATION, bearer(FORGED_TOKEN)),
        status().isUnauthorized(),
        "UNAUTHENTICATED",
        "life-tips-topics-unauthenticated",
        "주제 목록 — 인증 누락/위조 (401 UNAUTHENTICATED)");

    // 401 — 액세스 토큰 만료
    perform(
        get("/api/v1/life-tips/topics/{topicCode}/tips", "MOVING_IN")
            .header(HttpHeaders.AUTHORIZATION, bearer(expiredAccessToken())),
        status().isUnauthorized(),
        "TOKEN_EXPIRED",
        "life-tips-topic-tips-token-expired",
        "주제별 팁 — 액세스 토큰 만료 (401 TOKEN_EXPIRED)");
  }

  // --- field descriptors ---

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

  private static List<FieldDescriptor> topicsResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"),
        field("data.topics[].code", JsonFieldType.STRING, "주제 코드(UPPER_SNAKE, 언어 무관)"),
        field("data.topics[].name", JsonFieldType.STRING, "번역된 주제 표시명"),
        errorNull());
  }

  private static List<FieldDescriptor> tipsResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"),
        field("data.tips[].id", JsonFieldType.STRING, "팁 식별자(ObjectId hex, 언어 무관)"),
        field("data.tips[].title", JsonFieldType.STRING, "번역된 제목"),
        field("data.tips[].content", JsonFieldType.STRING, "번역된 내용"),
        optField("data.tips[].imageUrl", JsonFieldType.STRING, "사진 URL(언어 무관, 없으면 null)"),
        errorNull());
  }

  private static List<FieldDescriptor> errorFields() {
    return List.of(
        fieldWithPath("success").type(JsonFieldType.BOOLEAN).description("성공 여부 — 에러는 항상 false"),
        fieldWithPath("data")
            .type(JsonFieldType.NULL)
            .optional()
            .description("에러 응답의 data는 항상 null"),
        fieldWithPath("error.code").type(JsonFieldType.STRING).description("에러 식별 코드(UPPER_SNAKE)"),
        fieldWithPath("error.message").type(JsonFieldType.STRING).description("사람이 읽는 설명"),
        fieldWithPath("error.errors").type(JsonFieldType.ARRAY).description("검증 실패 상세(그 외 빈 배열)"),
        fieldWithPath("error.errors[].field")
            .type(JsonFieldType.STRING)
            .optional()
            .description("검증 실패 필드"),
        fieldWithPath("error.errors[].reason")
            .type(JsonFieldType.STRING)
            .optional()
            .description("실패 사유"));
  }

  // --- seed / helpers ---

  private void seed() {
    topicRepository.save(topicDoc("MOVING_IN", 1, Map.of("en", "Moving In", "ko", "입주·이사")));
    topicRepository.save(topicDoc("TRANSPORT", 2, Map.of("en", "Transport", "ko", "교통")));
    tipRepository.save(
        tipDoc(
            "MOVING_IN",
            1,
            Map.of("en", "Resident registration", "ko", "전입신고"),
            Map.of("en", "File within 14 days.", "ko", "14일 이내에 신고하세요."),
            "https://cdn.kohere.app/life-tips/moving-in/1.png"));
    tipRepository.save(
        tipDoc(
            "MOVING_IN",
            2,
            Map.of("en", "Utilities", "ko", "공과금 개통"),
            Map.of("en", "Set up electricity, water, gas.", "ko", "전기·수도·가스를 개통하세요."),
            "https://cdn.kohere.app/life-tips/moving-in/2.png"));
  }

  private static LifeTipTopicDocument topicDoc(String code, int order, Map<String, String> name) {
    return LifeTipTopicDocument.builder().id(code).name(name).order(order).build();
  }

  private static LifeTipDocument tipDoc(
      String topicCode,
      int order,
      Map<String, String> title,
      Map<String, String> content,
      String imageUrl) {
    return LifeTipDocument.builder()
        .topicCode(topicCode)
        .order(order)
        .title(title)
        .content(content)
        .imageUrl(imageUrl)
        .build();
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
        .andDo(
            document(
                identifier,
                resource(
                    ResourceSnippetParameters.builder()
                        .summary(summary)
                        .description("실패 응답 — 공통 래퍼(success=false·data=null·error).")
                        .responseFields(errorFields())
                        .build())));
  }

  private static String bearer(String token) {
    return "Bearer " + token;
  }
}
