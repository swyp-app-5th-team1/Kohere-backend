package com.kohere.listing.infrastructure;

import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.document;
import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.resourceDetails;
import static com.kohere.docs.ApiDocsErrors.errorSnippet;
import static com.kohere.docs.DocsTokens.bearer;
import static com.kohere.docs.DocsTokens.expiredAccessToken;
import static com.kohere.docs.ListingDocsFields.ADMIN_LISTING_400;
import static com.kohere.docs.ListingDocsFields.ADMIN_LISTING_401;
import static com.kohere.docs.ListingDocsFields.ADMIN_LISTING_403;
import static com.kohere.docs.ListingDocsFields.ADMIN_LISTING_404;
import static com.kohere.docs.ListingDocsFields.ADMIN_LISTING_APPROVAL_DESCRIPTION;
import static com.kohere.docs.ListingDocsFields.ADMIN_LISTING_APPROVAL_SUMMARY;
import static com.kohere.docs.ListingDocsFields.ADMIN_LISTING_DETAIL_DESCRIPTION;
import static com.kohere.docs.ListingDocsFields.ADMIN_LISTING_DETAIL_SUMMARY;
import static com.kohere.docs.ListingDocsFields.ADMIN_LISTING_LIST_DESCRIPTION;
import static com.kohere.docs.ListingDocsFields.ADMIN_LISTING_LIST_SUMMARY;
import static com.kohere.docs.ListingDocsFields.ADMIN_LISTING_REJECTION_DESCRIPTION;
import static com.kohere.docs.ListingDocsFields.ADMIN_LISTING_REJECTION_SUMMARY;
import static com.kohere.docs.ListingDocsFields.adminListingPageResponseFields;
import static com.kohere.docs.ListingDocsFields.adminListingResponseFields;
import static com.kohere.docs.ListingDocsFields.rejectionRequestFields;
import static org.mockito.BDDMockito.given;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.post;
import static org.springframework.restdocs.payload.PayloadDocumentation.requestFields;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;
import static org.springframework.restdocs.request.RequestDocumentation.pathParameters;
import static org.springframework.restdocs.request.RequestDocumentation.queryParameters;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kohere.TestcontainersConfiguration;
import com.kohere.common.security.JwtProperties;
import com.kohere.common.security.JwtTokenService;
import com.kohere.docs.ApiDocsTags;
import com.kohere.listing.domain.Listing;
import com.kohere.listing.domain.ListingRepository;
import com.kohere.user.api.UserAccountService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.restdocs.RestDocumentationContextProvider;
import org.springframework.restdocs.RestDocumentationExtension;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 관리자 매물 심사 4종({@code /api/v1/admin/listings})의 REST Docs 스니펫 생성 테스트다(US-3-7).
 *
 * <p>태그는 {@link ApiDocsTags#LISTINGS}를 그대로 쓴다 — 매물 도메인 소유라 같은 그룹에 두고, 새 태그를 만들면 {@code
 * swagger-ui-initializer.js}의 {@code TAG_ORDER}도 함께 갱신해야 한다(ADR-0017).
 *
 * <p>인가가 이 API의 핵심이라 에러 스니펫에 비중을 둔다 — 관리자 아님(403)·온보딩 토큰(403)·심사 대상 아님(409)·사유 누락(400)·없는
 * 매물(404)·토큰 부재/위조/만료(401).
 *
 * <p>cross-module 협력({@code user :: api}의 {@code userType})은 {@link MockitoBean}으로 대체하고 access 토큰은
 * {@link JwtTokenService}로 직접 발급한다(test-strategy §4).
 */
@SpringBootTest
@ExtendWith(RestDocumentationExtension.class)
@ActiveProfiles("test")
@Testcontainers
@Import(TestcontainersConfiguration.class)
class AdminListingDocsTest {

  /** 심사 권한이 있는 관리자 계정이다. */
  private static final long ADMIN_ID = 7L;

  /** 정식 회원이지만 관리자가 아니라 서비스가 403으로 거르는 계정이다. */
  private static final long LANDLORD_ID = 42L;

  private static final String LISTINGS_COLLECTION = "listings";
  private static final String LISTING_CATALOG_COLLECTION = "listingCatalog";

  /** 시드 매물 중 심사 대상으로 되돌려 쓸 문서다. */
  private static final String PENDING_LISTING_ID = ListingTestSeeds.LISTING_ID;

  private static final String MISSING_LISTING_ID = "68e0000000000000000000ff";

  private static final String REJECTION_BODY =
      """
      {
        "reason": "사업자등록번호와 매물 주소가 일치하지 않습니다."
      }
      """;

  // 다른 키로 서명한 위조 access 토큰. 401 예시도 구조상 JWT여야 restdocs-api-spec이 bearerAuth 보안 스킴을
  // 도출해 Swagger 자물쇠가 유지된다(ListingDocsTest와 동일 처리).
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

  @Container @ServiceConnection static MongoDBContainer mongo = new MongoDBContainer("mongo:7.0");

  @Autowired private WebApplicationContext context;
  @Autowired private MongoTemplate mongoTemplate;
  @Autowired private ListingRepository listingRepository;
  @Autowired private JwtTokenService jwtTokenService;
  @Autowired private JwtProperties jwtProperties;

  @MockitoBean private UserAccountService userAccountService;

  private MockMvc mockMvc;

  /** REST Docs용 MockMvc를 만들고, 카탈로그를 시드한 뒤 시드 매물 하나를 심사 대기로 되돌린다. */
  @BeforeEach
  void setUp(RestDocumentationContextProvider restDocumentation) {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .apply(springSecurity())
            .apply(documentationConfiguration(restDocumentation))
            .build();
    mongoTemplate.getCollection(LISTINGS_COLLECTION).deleteMany(new Document());
    mongoTemplate.getCollection(LISTING_CATALOG_COLLECTION).deleteMany(new Document());
    ListingTestSeeds.seedCatalog(mongoTemplate, LISTING_CATALOG_COLLECTION);
    ListingTestSeeds.seedListings(mongoTemplate, LISTINGS_COLLECTION);
    makePending(PENDING_LISTING_ID);

    given(userAccountService.getUserType(ADMIN_ID)).willReturn("ADMIN");
    given(userAccountService.getUserType(LANDLORD_ID)).willReturn("LANDLORD");
  }

  @Test
  void 문서스니펫생성_심사목록_모든상태와상태별필터() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/admin/listings")
                .header(HttpHeaders.AUTHORIZATION, bearer(adminToken()))
                .queryParam("status", "PENDING")
                .queryParam("page", "0")
                .queryParam("size", "20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[0].listing.status").value("PENDING"))
        // 세입자 응답이 감추는 값이 심사 응답에는 들어 있다.
        .andExpect(jsonPath("$.data.content[0].businessRegistrationNumber").isString())
        .andExpect(jsonPath("$.data.content[0].consents.privacyPolicyAgreed").value(true))
        .andDo(
            document(
                "admin-listing-list",
                resourceDetails()
                    .tag(ApiDocsTags.LISTINGS)
                    .summary(ADMIN_LISTING_LIST_SUMMARY)
                    .description(ADMIN_LISTING_LIST_DESCRIPTION),
                queryParameters(
                    parameterWithName("status").description("상태 필터. 콤마로 여러 개. 생략하면 전체").optional(),
                    parameterWithName("page").description("0부터 시작하는 페이지 번호").optional(),
                    parameterWithName("size").description("페이지 크기(기본 20, 최대 100)").optional()),
                responseFields(adminListingPageResponseFields())));
  }

  @Test
  void 문서스니펫생성_심사상세_전필드반환() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/admin/listings/{listingId}", PENDING_LISTING_ID)
                .header(HttpHeaders.AUTHORIZATION, bearer(adminToken())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.landlordId").isNumber())
        .andExpect(jsonPath("$.data.consents.version").isString())
        .andDo(
            document(
                "admin-listing-detail",
                resourceDetails()
                    .tag(ApiDocsTags.LISTINGS)
                    .summary(ADMIN_LISTING_DETAIL_SUMMARY)
                    .description(ADMIN_LISTING_DETAIL_DESCRIPTION),
                pathParameters(parameterWithName("listingId").description("심사할 매물 ID")),
                responseFields(adminListingResponseFields())));
  }

  @Test
  void 문서스니펫생성_승인_공개상태로전이() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/admin/listings/{listingId}/approval", PENDING_LISTING_ID)
                .header(HttpHeaders.AUTHORIZATION, bearer(adminToken())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.listing.status").value("PUBLISHED"))
        .andDo(
            document(
                "admin-listing-approval",
                resourceDetails()
                    .tag(ApiDocsTags.LISTINGS)
                    .summary(ADMIN_LISTING_APPROVAL_SUMMARY)
                    .description(ADMIN_LISTING_APPROVAL_DESCRIPTION),
                pathParameters(parameterWithName("listingId").description("승인할 매물 ID")),
                responseFields(adminListingResponseFields())));
  }

  @Test
  void 문서스니펫생성_반려_사유저장() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/admin/listings/{listingId}/rejection", PENDING_LISTING_ID)
                .header(HttpHeaders.AUTHORIZATION, bearer(adminToken()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(REJECTION_BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.listing.status").value("REJECTED"))
        .andExpect(jsonPath("$.data.rejectionReason").value("사업자등록번호와 매물 주소가 일치하지 않습니다."))
        .andDo(
            document(
                "admin-listing-rejection",
                resourceDetails()
                    .tag(ApiDocsTags.LISTINGS)
                    .summary(ADMIN_LISTING_REJECTION_SUMMARY)
                    .description(ADMIN_LISTING_REJECTION_DESCRIPTION),
                pathParameters(parameterWithName("listingId").description("반려할 매물 ID")),
                requestFields(rejectionRequestFields()),
                responseFields(adminListingResponseFields())));
  }

  @Test
  void 문서스니펫생성_반려사유누락_400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/admin/listings/{listingId}/rejection", PENDING_LISTING_ID)
                .header(HttpHeaders.AUTHORIZATION, bearer(adminToken()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"reason\": \"   \" }"))
        .andExpect(status().isBadRequest())
        .andDo(
            errorSnippet(
                "admin-listing-rejection-invalid-input",
                ApiDocsTags.LISTINGS,
                ADMIN_LISTING_REJECTION_SUMMARY,
                ADMIN_LISTING_REJECTION_DESCRIPTION,
                ADMIN_LISTING_400));
  }

  @Test
  void 문서스니펫생성_관리자아님_403() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/admin/listings")
                .header(HttpHeaders.AUTHORIZATION, bearer(landlordToken())))
        .andExpect(status().isForbidden())
        .andDo(
            errorSnippet(
                "admin-listing-list-forbidden",
                ApiDocsTags.LISTINGS,
                ADMIN_LISTING_LIST_SUMMARY,
                ADMIN_LISTING_LIST_DESCRIPTION,
                ADMIN_LISTING_403));
  }

  @Test
  void 문서스니펫생성_온보딩토큰_403() throws Exception {
    // 매처(hasRole("USER"))가 컨트롤러 앞에서 끊는다. 매처가 없으면 여기까지 도달한다.
    mockMvc
        .perform(
            get("/api/v1/admin/listings")
                .header(
                    HttpHeaders.AUTHORIZATION,
                    bearer(jwtTokenService.issueOnboardingToken(ADMIN_ID))))
        .andExpect(status().isForbidden());
  }

  @Test
  void 사후반려_공개매물을_상태무관하게_내린다() throws Exception {
    // 반려는 승인과 달리 상태를 가리지 않는다. 시드의 두 번째 매물은 PUBLISHED 그대로다 —
    // 문제가 발견된 공개 매물을 내리는 유일한 수단이라 409가 아니라 200이어야 한다.
    mockMvc
        .perform(
            post("/api/v1/admin/listings/{listingId}/rejection", ListingTestSeeds.SECOND_LISTING_ID)
                .header(HttpHeaders.AUTHORIZATION, bearer(adminToken()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(REJECTION_BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.listing.status").value("REJECTED"));
  }

  @Test
  void 재승인_잘못반려한매물을_되살린다() throws Exception {
    // 승인도 상태를 가리지 않는다. 관리자의 오판을 되돌릴 수단이 서버에 있어야 한다.
    mockMvc
        .perform(
            post("/api/v1/admin/listings/{listingId}/rejection", PENDING_LISTING_ID)
                .header(HttpHeaders.AUTHORIZATION, bearer(adminToken()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(REJECTION_BODY))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            post("/api/v1/admin/listings/{listingId}/approval", PENDING_LISTING_ID)
                .header(HttpHeaders.AUTHORIZATION, bearer(adminToken())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.listing.status").value("PUBLISHED"))
        .andExpect(jsonPath("$.data.rejectionReason").doesNotExist());
  }

  @Test
  void 문서스니펫생성_없는매물_404() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/admin/listings/{listingId}", MISSING_LISTING_ID)
                .header(HttpHeaders.AUTHORIZATION, bearer(adminToken())))
        .andExpect(status().isNotFound())
        .andDo(
            errorSnippet(
                "admin-listing-detail-not-found",
                ApiDocsTags.LISTINGS,
                ADMIN_LISTING_DETAIL_SUMMARY,
                ADMIN_LISTING_DETAIL_DESCRIPTION,
                ADMIN_LISTING_404));
  }

  @Test
  void 문서스니펫생성_인증실패_401() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/admin/listings").header(HttpHeaders.AUTHORIZATION, bearer(FORGED_TOKEN)))
        .andExpect(status().isUnauthorized())
        .andDo(
            errorSnippet(
                "admin-listing-list-unauthenticated",
                ApiDocsTags.LISTINGS,
                ADMIN_LISTING_LIST_SUMMARY,
                ADMIN_LISTING_LIST_DESCRIPTION,
                ADMIN_LISTING_401));

    mockMvc
        .perform(
            get("/api/v1/admin/listings")
                .header(HttpHeaders.AUTHORIZATION, bearer(expiredAccessToken(jwtProperties))))
        .andExpect(status().isUnauthorized());
  }

  /** 시드 매물 하나를 심사 대기로 되돌린다. 시드는 둘 다 공개 상태라 그대로는 심사할 것이 없다. */
  private void makePending(String listingId) {
    Listing seeded = listingRepository.findById(listingId).orElseThrow();
    listingRepository.save(
        seeded.toBuilder().status(Listing.ListingStatus.PENDING).rejectionReason(null).build());
  }

  private String adminToken() {
    return jwtTokenService.issueAccessToken(ADMIN_ID);
  }

  private String landlordToken() {
    return jwtTokenService.issueAccessToken(LANDLORD_ID);
  }
}
