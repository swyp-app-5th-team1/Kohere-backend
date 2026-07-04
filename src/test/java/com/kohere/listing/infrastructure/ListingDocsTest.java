package com.kohere.listing.infrastructure;

import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.document;
import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.resourceDetails;
import static com.epages.restdocs.apispec.ResourceDocumentation.resource;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.delete;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.post;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;
import static org.springframework.restdocs.request.RequestDocumentation.pathParameters;
import static org.springframework.restdocs.request.RequestDocumentation.queryParameters;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.epages.restdocs.apispec.ResourceSnippetParameters;
import com.kohere.TestcontainersConfiguration;
import com.kohere.common.security.JwtProperties;
import com.kohere.common.security.JwtTokenService;
import com.kohere.listing.domain.ListingRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import javax.crypto.SecretKey;
import org.bson.Document;
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
import org.springframework.restdocs.mockmvc.RestDocumentationResultHandler;
import org.springframework.restdocs.payload.FieldDescriptor;
import org.springframework.restdocs.payload.JsonFieldType;
import org.springframework.restdocs.request.ParameterDescriptor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * listing 모듈의 구현 완료 API를 Swagger UI(OpenAPI)에 노출하기 위한 REST Docs 테스트다. 이 프로젝트는 컨트롤러 자동 스캔이 아니라
 * DocsTest 스니펫으로 Swagger를 생성한다.
 */
@SpringBootTest
@ExtendWith(RestDocumentationExtension.class)
@ActiveProfiles("test")
@TestPropertySource(properties = "app.mongo.indexes-enabled=true")
@Testcontainers
@Import(TestcontainersConfiguration.class)
class ListingDocsTest {

  private static final String LISTING_ID = ListingSeedFixtures.GOSIWON_001_ID;
  private static final String MISSING_LISTING_ID = "6858e20000000000000000ff";
  private static final String LISTINGS_LIST_SUMMARY = "지도 바텀시트 매물 리스트 조회";
  private static final String LISTINGS_LIST_DESCRIPTION =
      "현재 지도 화면의 남서/북동 좌표를 기준으로 공개 매물 카드 리스트를 조회한다. "
          + "지도 범위 좌표가 전달되면 서버가 전체 범위를 20% 확장해 조회하며, "
          + "거리 표시와 DISTANCE 정렬은 프론트가 보낸 별도 중심 좌표가 아니라 요청 bbox의 원본 중심점을 기준으로 계산한다. "
          + "응답 content[]의 1개 항목은 roomOffer가 아니라 Listing(고시원/건물/숙소 매물) 1개다. "
          + "필터가 없으면 해당 매물의 active roomOffer 전체를 집계하고, "
          + "필터가 있으면 조건을 만족하는 active roomOffer만 집계한다. "
          + "월세·보증금·관리비·계약기간은 집계 대상 roomOffer의 최저~최고 범위로 내려간다. "
          + "월세·보증금·일반 조건 태그는 roomOffer 기준으로 적용하고, 매물 종류·NO_ARC·지도 범위는 Listing 기준으로 적용한다. "
          + "전입신고 가능 여부는 conditions=ADDRESS_REGISTRATION으로 필터링한다. "
          + "No ARC 필터는 별도 파라미터가 아니라 conditions=NO_ARC로 요청하며, ARC 없이 가능한 매물(propertyPolicies.arcRequired=false)만 반환한다. "
          + "conditions에 NO_ARC를 넣지 않으면 ARC 조건은 적용하지 않는다. "
          + "같은 매물에 조건에 맞는 roomOffer가 여러 개 있어도 같은 listingId는 한 번만 내려간다. "
          + "프론트는 minMonthlyRent~maxMonthlyRent, minDeposit~maxDeposit, minMaintenanceFee~maxMaintenanceFee, "
          + "minStayMonths~maxStayMonths로 목록 카드의 범위 문구를 만들고, 카드 선택 시 listingId로 상세 API를 호출하면 된다. "
          + "이 API는 지도 바텀시트 리스트 카드용이며, 지도 핀/클러스터 데이터는 별도 지도 API에서 제공한다.";
  private static final String LISTINGS_MAP_SUMMARY = "지도 마커 조회";
  private static final String LISTINGS_MAP_DESCRIPTION =
      "현재 지도 화면의 bbox(swLat, swLng, neLat, neLng)에 포함되는 공개 매물의 개별 마커 좌표를 조회한다. "
          + "bbox 네 좌표는 모두 필수이며, 서버가 전체 범위를 20% 확장해 조회한다. "
          + "필터 적용 기준은 리스트 API와 같지만, 응답은 매물 카드가 아니라 Listing 마커 단위다. "
          + "전입신고 가능 여부는 conditions=ADDRESS_REGISTRATION으로 필터링하고, "
          + "No ARC 필터는 conditions=NO_ARC로 요청해 ARC 없이 가능한 매물만 조회한다. "
          + "한 매물 안의 roomOffer가 여러 개 매칭되어도 지도 마커는 해당 listingId로 1개만 내려간다. "
          + "서버는 클러스터링하지 않고 listingId·lat·lng만 내려주며, 프론트 지도 SDK가 화면 기준으로 마커를 묶는다.";
  private static final String LISTINGS_SEARCH_SUMMARY = "키워드 장소 검색과 주변 매물 조회";
  private static final String LISTINGS_SEARCH_DESCRIPTION =
      "학교명·지역명·지하철역명을 keyword 하나로 받아 POI(searchPlaces) 사전에서 장소를 찾고, "
          + "매칭된 장소 좌표 기준 3km 이내의 공개 매물 카드 목록을 반환한다. "
          + "일부 검색어와 별칭 검색을 지원한다. 예를 들어 keyword=연세는 연세대학교, keyword=신촌은 신촌역으로 매칭될 수 있다. "
          + "정렬 기본값은 DISTANCE이며, distanceMeters는 matchedPlace 좌표에서 매물까지의 직선 거리다. "
          + "content[]는 리스트 API와 같은 Listing 단위 카드이며, 조건을 통과한 roomOffer들의 가격·보증금·관리비·계약기간 범위를 포함한다. "
          + "검색어가 POI 사전에 없으면 404가 아니라 200 OK로 matchedPlace=null, content=[]를 반환한다. "
          + "프론트는 matchedPlace=null이면 '검색된 장소가 없어요', matchedPlace가 있고 content=[]이면 '이 주변에 매물이 없어요'처럼 구분해 표시할 수 있다.";
  private static final String LISTING_DETAIL_SUMMARY = "매물 상세 조회";
  private static final String LISTING_DETAIL_DESCRIPTION =
      "매물 카드나 지도 핀에서 선택한 단일 매물의 상세 정보를 조회한다. "
          + "상세 화면의 이미지, 각 방 정보, 가격 정보, 매물 정보, 건물 정보, 공용시설, 위치 및 주변 정보 섹션을 구성하는 데이터를 반환한다. "
          + "summary는 상세 상단 가격·보증금·관리비·계약기간·이미지 개수·방 개수·표시 태그를 프론트가 재계산하지 않도록 ACTIVE roomOffer 기준으로 집계한다. "
          + "NO_ARC는 roomOffer에 저장하지 않는 가상 필터라 propertyPolicies.arcRequired=false일 때 summary.conditions에 파생해서 포함한다. "
          + "roomOffers[]는 상세 화면 Room Types에 실제 노출할 ACTIVE 방 상품만 포함한다. "
          + "reviewSummary.reviewCount는 리뷰 도메인 도입 전까지 0으로 내려주며, 문의 수는 채팅/문의 기능 고도화 때 별도 계약으로 추가한다. "
          + "고시원 매물의 propertyInfo.featureSummary는 활성 방 상품들이 가진 조건 태그의 합집합이다. "
          + "상세 화면의 작은 지도는 응답의 locationInfo.location 좌표를 사용해 프론트에서 렌더링한다. "
          + "공개 상태가 아닌 매물이나 존재하지 않는 매물은 LISTING_NOT_FOUND를 반환한다.";
  private static final String FAVORITE_ADD_SUMMARY = "매물 찜 등록";
  private static final String FAVORITE_ADD_DESCRIPTION =
      "로그인 사용자가 공개 매물을 찜한다. "
          + "신규 찜이면 201 Created, 이미 찜한 매물을 다시 요청하면 200 OK를 반환한다. "
          + "두 경우 모두 응답 body는 favorited=true와 변경 후 favoriteCount로 동일하므로, "
          + "프론트는 status와 무관하게 이 값으로 버튼 상태와 찜 수를 갱신하면 된다.";
  private static final String FAVORITE_REMOVE_SUMMARY = "매물 찜 해제";
  private static final String FAVORITE_REMOVE_DESCRIPTION =
      "로그인 사용자가 본인의 매물 찜을 해제한다. "
          + "이미 찜하지 않은 상태에서 다시 호출해도 에러가 아니며 200 OK와 favorited=false를 반환한다. "
          + "프론트는 응답의 favoriteCount로 카드/상세 화면의 찜 수를 갱신하면 된다.";
  private static final String FAVORITES_LIST_SUMMARY = "내 찜한 매물 목록";
  private static final String FAVORITES_LIST_DESCRIPTION =
      "로그인 사용자가 찜한 공개 매물을 최근 찜한 순으로 조회한다. "
          + "MVP에서는 별도 sort 파라미터를 받지 않고 favoritedAt desc로 고정한다. "
          + "응답 항목은 모두 현재 사용자가 찜한 매물이므로 favorited=true이며, "
          + "DRAFT/PAUSED/DELETED 등 공개 상태가 아닌 매물은 content와 totalElements에서 제외된다.";
  private static final String RECENT_LISTINGS_SUMMARY = "최근 본 매물 목록";
  private static final String RECENT_LISTINGS_DESCRIPTION =
      "로그인 사용자가 상세 화면에서 본 매물을 최신 조회순으로 최대 10개 반환한다. "
          + "프론트가 입력할 query parameter는 없으며, 상세 조회 API가 Authorization 토큰의 userId와 listingId로 최근 본 기록을 자동 저장한다. "
          + "같은 매물을 다시 보면 중복 생성하지 않고 viewedAt만 갱신한다. "
          + "DB에는 사용자별 최신 30개 기록까지만 보관하고, 조회 응답은 그중 현재 PUBLISHED 상태이며 활성 roomOffer가 있는 매물만 최대 10개 내려준다. "
          + "PAUSED/DELETED/DRAFT 매물은 사용자가 과거에 봤더라도 숨긴다. "
          + "응답 필드는 /api/v1/listings 카드 응답과 최대한 맞췄고, 최근 본 화면에서 정렬 기준을 확인할 수 있도록 viewedAt을 추가한다. "
          + "favorited는 현재 로그인 사용자의 실제 찜 여부라 하트 UI를 이 값만 보고 그리면 된다.";

  // 문서화용 위조 토큰. 401 예시에서도 bearerAuthJWT 보안 스킴이 안정적으로 생성되게 한다.
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
  @Autowired private org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;
  @Autowired private JwtProperties jwtProperties;
  @Autowired private JwtTokenService jwtTokenService;
  @Autowired private ListingRepository listingRepository;

  private MockMvc mockMvc;

  /** REST Docs용 MockMvc를 만들고, 문서 예시에 사용할 매물 seed 데이터를 매번 초기화한다. */
  @BeforeEach
  void setUp(RestDocumentationContextProvider restDocumentation) throws Exception {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .apply(springSecurity())
            .apply(documentationConfiguration(restDocumentation))
            .build();
    mongoTemplate.getCollection(ListingDocument.COLLECTION_NAME).deleteMany(new Document());
    mongoTemplate.getCollection(FavoriteDocument.COLLECTION_NAME).deleteMany(new Document());
    mongoTemplate.getCollection(RecentListingDocument.COLLECTION_NAME).deleteMany(new Document());
    mongoTemplate.getCollection(SearchPlaceDocument.COLLECTION_NAME).deleteMany(new Document());
    new ListingSeedRunner(listingRepository).run(null);
    new SearchPlaceSeedChangeUnit().execution(mongoTemplate);
  }

  /** 매물 목록/상세 API를 호출해 Swagger 생성에 필요한 REST Docs 스니펫을 만든다. */
  @Test
  void generatesListingSnippets() throws Exception {
    String token = jwtTokenService.issueAccessToken(1L);

    mockMvc
        .perform(
            get("/api/v1/listings")
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .param("swLat", "37.45920")
                .param("swLng", "126.95120")
                .param("neLat", "37.45946")
                .param("neLng", "126.95141")
                .param("maxBudget", "500000")
                .param("maxDeposit", "500000")
                .param("type", "GOSIWON")
                .param("conditions", "FEMALE_ONLY")
                .param("conditions", "ADDRESS_REGISTRATION")
                .param("conditions", "NO_ARC")
                .param("sort", "PRICE_ASC")
                .param("page", "0")
                .param("size", "20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[0].listingId").value(LISTING_ID))
        .andExpect(jsonPath("$.data.content[0].minMonthlyRent").value(300000))
        .andExpect(jsonPath("$.data.content[0].maxMonthlyRent").value(300000))
        .andExpect(jsonPath("$.data.content[0].availableCount").doesNotExist())
        .andDo(
            document(
                "listings-list",
                resourceDetails()
                    .summary(LISTINGS_LIST_SUMMARY)
                    .description(LISTINGS_LIST_DESCRIPTION),
                queryParameters(listQueryParameters()),
                responseFields(listResponseFields())));

    mockMvc
        .perform(
            get("/api/v1/listings/search")
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .param("keyword", "서울대")
                .param("sort", "DISTANCE")
                .param("page", "0")
                .param("size", "20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.matchedPlace.name").value("서울대학교"))
        .andExpect(jsonPath("$.data.content[0].listingId").value(LISTING_ID))
        .andExpect(jsonPath("$.data.content[0].minMonthlyRent").value(300000))
        .andExpect(jsonPath("$.data.content[0].maxMonthlyRent").value(300000))
        .andExpect(jsonPath("$.data.content[0].availableCount").doesNotExist())
        .andDo(
            document(
                "listings-search",
                resourceDetails()
                    .summary(LISTINGS_SEARCH_SUMMARY)
                    .description(LISTINGS_SEARCH_DESCRIPTION),
                queryParameters(searchQueryParameters()),
                responseFields(searchResponseFields())));

    mockMvc
        .perform(
            get("/api/v1/listings/search")
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .param("keyword", "없는장소")
                .param("page", "0")
                .param("size", "20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.matchedPlace").value(nullValue()))
        .andExpect(jsonPath("$.data.content").isEmpty())
        .andDo(
            document(
                "listings-search-empty-place",
                resourceDetails()
                    .summary(LISTINGS_SEARCH_SUMMARY)
                    .description(LISTINGS_SEARCH_DESCRIPTION),
                queryParameters(searchQueryParameters()),
                responseFields(searchEmptyPlaceResponseFields())));

    mockMvc
        .perform(
            get("/api/v1/listings/map")
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .param("swLat", "37.45920")
                .param("swLng", "126.95120")
                .param("neLat", "37.45946")
                .param("neLng", "126.95141")
                .param("maxBudget", "500000")
                .param("maxDeposit", "500000")
                .param("type", "GOSIWON")
                .param("conditions", "FEMALE_ONLY")
                .param("conditions", "ADDRESS_REGISTRATION")
                .param("conditions", "NO_ARC"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.markers[0].listingId").value(LISTING_ID))
        .andExpect(jsonPath("$.data.total").value(1))
        .andDo(
            document(
                "listings-map",
                resourceDetails()
                    .summary(LISTINGS_MAP_SUMMARY)
                    .description(LISTINGS_MAP_DESCRIPTION),
                queryParameters(mapQueryParameters()),
                responseFields(mapResponseFields())));

    mockMvc
        .perform(
            get("/api/v1/listings/{listingId}", LISTING_ID)
                .header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.listingId").value(LISTING_ID))
        .andExpect(jsonPath("$.data.summary.minMonthlyRent").value(300000))
        .andExpect(jsonPath("$.data.summary.activeRoomOfferCount").value(1))
        .andExpect(jsonPath("$.data.summary.conditions", hasItem("NO_ARC")))
        .andExpect(jsonPath("$.data.reviewSummary.reviewCount").value(0))
        .andDo(
            document(
                "listing-detail",
                resourceDetails()
                    .summary(LISTING_DETAIL_SUMMARY)
                    .description(LISTING_DETAIL_DESCRIPTION),
                pathParameters(
                    parameterWithName("listingId")
                        .description("상세 조회할 매물 식별자(ObjectId 24자리 hex 문자열)")),
                responseFields(detailResponseFields())));

    mockMvc
        .perform(
            post("/api/v1/listings/{listingId}/favorite", LISTING_ID)
                .header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.favorited").value(true))
        .andExpect(jsonPath("$.data.favoriteCount").value(1))
        .andDo(
            document(
                "listing-favorite-add-created",
                resourceDetails()
                    .summary(FAVORITE_ADD_SUMMARY)
                    .description(FAVORITE_ADD_DESCRIPTION),
                pathParameters(favoritePathParameters()),
                responseFields(favoriteToggleResponseFields())));

    mockMvc
        .perform(
            post("/api/v1/listings/{listingId}/favorite", LISTING_ID)
                .header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.favorited").value(true))
        .andExpect(jsonPath("$.data.favoriteCount").value(1))
        .andDo(
            document(
                "listing-favorite-add-existing",
                resourceDetails()
                    .summary("매물 찜 등록 — 이미 찜한 상태")
                    .description(
                        FAVORITE_ADD_DESCRIPTION + " 이미 찜한 매물을 다시 호출해도 중복 저장하지 않고 현재 상태를 반환한다."),
                pathParameters(favoritePathParameters()),
                responseFields(favoriteToggleResponseFields())));

    mockMvc
        .perform(
            get("/api/v1/users/me/favorites")
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .param("page", "0")
                .param("size", "20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[0].listingId").value(LISTING_ID))
        .andExpect(jsonPath("$.data.content[0].favorited").value(true))
        .andDo(
            document(
                "my-favorites-list",
                resourceDetails()
                    .summary(FAVORITES_LIST_SUMMARY)
                    .description(FAVORITES_LIST_DESCRIPTION),
                queryParameters(favoritesQueryParameters()),
                responseFields(favoritesResponseFields())));

    mockMvc
        .perform(
            get("/api/v1/users/me/recent-listings")
                .header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[0].listingId").value(LISTING_ID))
        .andExpect(jsonPath("$.data.content[0].favorited").value(true))
        .andExpect(jsonPath("$.data.content[0].viewedAt").isString())
        .andDo(
            document(
                "my-recent-listings",
                resourceDetails()
                    .summary(RECENT_LISTINGS_SUMMARY)
                    .description(RECENT_LISTINGS_DESCRIPTION),
                responseFields(recentListingsResponseFields())));

    mockMvc
        .perform(
            delete("/api/v1/listings/{listingId}/favorite", LISTING_ID)
                .header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.favorited").value(false))
        .andExpect(jsonPath("$.data.favoriteCount").value(0))
        .andDo(
            document(
                "listing-favorite-remove",
                resourceDetails()
                    .summary(FAVORITE_REMOVE_SUMMARY)
                    .description(FAVORITE_REMOVE_DESCRIPTION),
                pathParameters(favoritePathParameters()),
                responseFields(favoriteToggleResponseFields())));
  }

  /** 스펙의 "발생 가능한 에러"를 실제로 트리거해 status·error.code와 실패 응답 스니펫을 함께 만든다. */
  @Test
  void generatesListingErrorSnippets() throws Exception {
    String token = jwtTokenService.issueAccessToken(1L);
    String expiredToken = expiredAccessToken();

    // ===== GET /listings =====
    perform(
        get("/api/v1/listings").header(HttpHeaders.AUTHORIZATION, bearer(FORGED_TOKEN)),
        status().isUnauthorized(),
        "UNAUTHENTICATED",
        "listings-list-unauthenticated",
        LISTINGS_LIST_SUMMARY,
        LISTINGS_LIST_DESCRIPTION);

    perform(
        get("/api/v1/listings").header(HttpHeaders.AUTHORIZATION, bearer(expiredToken)),
        status().isUnauthorized(),
        "TOKEN_EXPIRED",
        "listings-list-token-expired",
        LISTINGS_LIST_SUMMARY,
        LISTINGS_LIST_DESCRIPTION);

    perform(
        get("/api/v1/listings")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .param("sort", "UNKNOWN"),
        status().isBadRequest(),
        "INVALID_INPUT",
        "listings-list-invalid-sort",
        LISTINGS_LIST_SUMMARY,
        LISTINGS_LIST_DESCRIPTION);

    perform(
        get("/api/v1/listings")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .param("minBudget", "700000")
            .param("maxBudget", "300000"),
        status().isBadRequest(),
        "INVALID_INPUT",
        "listings-list-invalid-budget-range",
        LISTINGS_LIST_SUMMARY,
        LISTINGS_LIST_DESCRIPTION);

    perform(
        get("/api/v1/listings")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .param("size", "101"),
        status().isBadRequest(),
        "INVALID_INPUT",
        "listings-list-invalid-page-size",
        LISTINGS_LIST_SUMMARY,
        LISTINGS_LIST_DESCRIPTION);

    perform(
        get("/api/v1/listings")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .param("swLat", "37.45"),
        status().isBadRequest(),
        "LISTING_INVALID_BBOX",
        "listings-list-invalid-bbox",
        LISTINGS_LIST_SUMMARY,
        LISTINGS_LIST_DESCRIPTION);

    perform(
        get("/api/v1/listings")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .param("sort", "DISTANCE"),
        status().isBadRequest(),
        "LISTING_INVALID_SORT_PARAM",
        "listings-list-invalid-distance-sort",
        LISTINGS_LIST_SUMMARY,
        LISTINGS_LIST_DESCRIPTION);

    // ===== GET /listings/search =====
    perform(
        get("/api/v1/listings/search").header(HttpHeaders.AUTHORIZATION, bearer(token)),
        status().isBadRequest(),
        "INVALID_INPUT",
        "listings-search-invalid-keyword-missing",
        LISTINGS_SEARCH_SUMMARY,
        LISTINGS_SEARCH_DESCRIPTION);

    perform(
        get("/api/v1/listings/search")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .param("keyword", "   "),
        status().isBadRequest(),
        "INVALID_INPUT",
        "listings-search-invalid-keyword-blank",
        LISTINGS_SEARCH_SUMMARY,
        LISTINGS_SEARCH_DESCRIPTION);

    perform(
        get("/api/v1/listings/search")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .param("keyword", "가".repeat(51)),
        status().isBadRequest(),
        "INVALID_INPUT",
        "listings-search-invalid-keyword-too-long",
        LISTINGS_SEARCH_SUMMARY,
        LISTINGS_SEARCH_DESCRIPTION);

    perform(
        get("/api/v1/listings/search")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .param("keyword", "서울대")
            .param("size", "101"),
        status().isBadRequest(),
        "INVALID_INPUT",
        "listings-search-invalid-page-size",
        LISTINGS_SEARCH_SUMMARY,
        LISTINGS_SEARCH_DESCRIPTION);

    // ===== GET /listings/map =====
    perform(
        get("/api/v1/listings/map")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .param("swLat", "37.45"),
        status().isBadRequest(),
        "LISTING_INVALID_BBOX",
        "listings-map-invalid-bbox",
        LISTINGS_MAP_SUMMARY,
        LISTINGS_MAP_DESCRIPTION);

    // ===== GET /listings/{listingId} =====
    perform(
        get("/api/v1/listings/{listingId}", MISSING_LISTING_ID)
            .header(HttpHeaders.AUTHORIZATION, bearer(token)),
        status().isNotFound(),
        "LISTING_NOT_FOUND",
        "listing-detail-not-found",
        LISTING_DETAIL_SUMMARY,
        LISTING_DETAIL_DESCRIPTION);

    perform(
        get("/api/v1/listings/{listingId}", LISTING_ID)
            .header(HttpHeaders.AUTHORIZATION, bearer(FORGED_TOKEN)),
        status().isUnauthorized(),
        "UNAUTHENTICATED",
        "listing-detail-unauthenticated",
        LISTING_DETAIL_SUMMARY,
        LISTING_DETAIL_DESCRIPTION);

    perform(
        get("/api/v1/listings/{listingId}", LISTING_ID)
            .header(HttpHeaders.AUTHORIZATION, bearer(expiredToken)),
        status().isUnauthorized(),
        "TOKEN_EXPIRED",
        "listing-detail-token-expired",
        LISTING_DETAIL_SUMMARY,
        LISTING_DETAIL_DESCRIPTION);

    // ===== POST/DELETE /listings/{listingId}/favorite =====
    perform(
        post("/api/v1/listings/{listingId}/favorite", LISTING_ID),
        status().isUnauthorized(),
        "UNAUTHENTICATED",
        "listing-favorite-add-unauthenticated",
        FAVORITE_ADD_SUMMARY,
        FAVORITE_ADD_DESCRIPTION);

    perform(
        post("/api/v1/listings/{listingId}/favorite", LISTING_ID)
            .header(HttpHeaders.AUTHORIZATION, bearer(expiredToken)),
        status().isUnauthorized(),
        "TOKEN_EXPIRED",
        "listing-favorite-add-token-expired",
        FAVORITE_ADD_SUMMARY,
        FAVORITE_ADD_DESCRIPTION);

    perform(
        post("/api/v1/listings/{listingId}/favorite", MISSING_LISTING_ID)
            .header(HttpHeaders.AUTHORIZATION, bearer(token)),
        status().isNotFound(),
        "LISTING_NOT_FOUND",
        "listing-favorite-add-not-found",
        FAVORITE_ADD_SUMMARY,
        FAVORITE_ADD_DESCRIPTION);

    perform(
        delete("/api/v1/listings/{listingId}/favorite", MISSING_LISTING_ID)
            .header(HttpHeaders.AUTHORIZATION, bearer(token)),
        status().isNotFound(),
        "LISTING_NOT_FOUND",
        "listing-favorite-remove-not-found",
        FAVORITE_REMOVE_SUMMARY,
        FAVORITE_REMOVE_DESCRIPTION);

    // ===== GET /users/me/favorites =====
    perform(
        get("/api/v1/users/me/favorites"),
        status().isUnauthorized(),
        "UNAUTHENTICATED",
        "my-favorites-list-unauthenticated",
        FAVORITES_LIST_SUMMARY,
        FAVORITES_LIST_DESCRIPTION);

    perform(
        get("/api/v1/users/me/favorites")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .param("size", "101"),
        status().isBadRequest(),
        "INVALID_INPUT",
        "my-favorites-list-invalid-page-size",
        FAVORITES_LIST_SUMMARY,
        FAVORITES_LIST_DESCRIPTION);

    // ===== GET /users/me/recent-listings =====
    perform(
        get("/api/v1/users/me/recent-listings"),
        status().isUnauthorized(),
        "UNAUTHENTICATED",
        "my-recent-listings-unauthenticated",
        RECENT_LISTINGS_SUMMARY,
        RECENT_LISTINGS_DESCRIPTION);

    perform(
        get("/api/v1/users/me/recent-listings")
            .header(HttpHeaders.AUTHORIZATION, bearer(expiredToken)),
        status().isUnauthorized(),
        "TOKEN_EXPIRED",
        "my-recent-listings-token-expired",
        RECENT_LISTINGS_SUMMARY,
        RECENT_LISTINGS_DESCRIPTION);
  }

  private void perform(
      MockHttpServletRequestBuilder request,
      ResultMatcher expectedStatus,
      String expectedCode,
      String identifier,
      String summary)
      throws Exception {
    perform(request, expectedStatus, expectedCode, identifier, summary, errorDescription());
  }

  private void perform(
      MockHttpServletRequestBuilder request,
      ResultMatcher expectedStatus,
      String expectedCode,
      String identifier,
      String summary,
      String description)
      throws Exception {
    mockMvc
        .perform(request)
        .andExpect(expectedStatus)
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.error.code").value(expectedCode))
        .andDo(errorSnippet(identifier, summary, description));
  }

  private static RestDocumentationResultHandler errorSnippet(
      String identifier, String summary, String description) {
    return document(
        identifier,
        resource(
            ResourceSnippetParameters.builder()
                .summary(summary)
                .description(description)
                .responseFields(errorFields())
                .build()));
  }

  private static String errorDescription() {
    return "실패 응답 — 공통 래퍼(success=false·data=null·error). 클라이언트는 error.code로 분기한다"
        + "(error-response-guide §1·§4).";
  }

  /** 목록 API의 query parameter 문서 정의다. */
  private static ParameterDescriptor[] listQueryParameters() {
    return new ParameterDescriptor[] {
      parameterWithName("swLat")
          .optional()
          .description("현재 지도 화면의 남서쪽 위도. bbox 조회 시 swLat/swLng/neLat/neLng 네 값을 모두 보내야 함"),
      parameterWithName("swLng")
          .optional()
          .description("현재 지도 화면의 남서쪽 경도. bbox 네 값이 모두 오면 서버가 전체 범위를 20% 확장해 조회"),
      parameterWithName("neLat").optional().description("현재 지도 화면의 북동쪽 위도. swLat보다 커야 함"),
      parameterWithName("neLng").optional().description("현재 지도 화면의 북동쪽 경도. swLng보다 커야 함"),
      parameterWithName("minBudget")
          .optional()
          .description(
              "roomOffer 월세 하한(KRW). 이 값을 만족하는 active roomOffer가 1개 이상 있는 매물만 반환하며, "
                  + "응답의 월세 범위는 조건을 통과한 roomOffer만 집계"),
      parameterWithName("maxBudget")
          .optional()
          .description(
              "roomOffer 월세 상한(KRW). 이 값을 만족하는 active roomOffer가 1개 이상 있는 매물만 반환하며, "
                  + "응답의 월세 범위는 조건을 통과한 roomOffer만 집계"),
      parameterWithName("minDeposit")
          .optional()
          .description("roomOffer 보증금 하한(KRW). 이 값을 만족하는 active roomOffer가 1개 이상 있는 매물만 반환"),
      parameterWithName("maxDeposit")
          .optional()
          .description("roomOffer 보증금 상한(KRW). 이 값을 만족하는 active roomOffer가 1개 이상 있는 매물만 반환"),
      parameterWithName("type")
          .optional()
          .description("Listing 기준 매물 종류 필터. 예: GOSIWON, CO_LIVING, SHARE_HOUSE"),
      parameterWithName("conditions")
          .optional()
          .description(
              "필터 칩 코드. FEMALE_ONLY, PRIVATE_BATH 같은 일반 조건은 같은 active roomOffer가 모두 만족해야 한다. "
                  + "MOVE_IN_NOW는 같은 roomOffer의 availableCount가 1 이상이어야 하며, "
                  + "ADDRESS_REGISTRATION은 전입신고 가능 필터다. "
                  + "NO_ARC는 roomOffer 태그가 아니라 Listing 정책 필터로 처리되어 propertyPolicies.arcRequired=false 매물만 반환한다. "
                  + "반복 파라미터 또는 콤마 구분 전송을 지원"),
      parameterWithName("sort")
          .optional()
          .description(
              "정렬 프리셋. RECOMMENDED는 기본 추천순, PRICE_ASC는 조건에 맞는 roomOffer들의 최저 월세 낮은 순, "
                  + "DISTANCE는 요청 bbox의 원본 중심점에서 가까운 Listing 순. sort=DISTANCE이면 bbox 네 좌표가 필요"),
      parameterWithName("page").optional().description("0-base 페이지 번호(기본 0)"),
      parameterWithName("size").optional().description("페이지 크기(기본 20, 최대 100)")
    };
  }

  /** 지도 마커 API의 query parameter 문서 정의다. */
  private static ParameterDescriptor[] mapQueryParameters() {
    return new ParameterDescriptor[] {
      parameterWithName("swLat").description("현재 지도 화면의 남서쪽 위도. 지도 마커 조회는 bbox 네 값이 모두 필수"),
      parameterWithName("swLng").description("현재 지도 화면의 남서쪽 경도"),
      parameterWithName("neLat").description("현재 지도 화면의 북동쪽 위도. swLat보다 커야 함"),
      parameterWithName("neLng").description("현재 지도 화면의 북동쪽 경도. swLng보다 커야 함"),
      parameterWithName("minBudget")
          .optional()
          .description("roomOffer 월세 하한(KRW). 같은 roomOffer가 만족해야 마커 반환"),
      parameterWithName("maxBudget")
          .optional()
          .description("roomOffer 월세 상한(KRW). 같은 roomOffer가 만족해야 마커 반환"),
      parameterWithName("minDeposit")
          .optional()
          .description("roomOffer 보증금 하한(KRW). 같은 roomOffer가 만족해야 마커 반환"),
      parameterWithName("maxDeposit")
          .optional()
          .description("roomOffer 보증금 상한(KRW). 같은 roomOffer가 만족해야 마커 반환"),
      parameterWithName("type")
          .optional()
          .description("Listing 기준 매물 종류 필터. 예: GOSIWON, CO_LIVING, SHARE_HOUSE"),
      parameterWithName("conditions")
          .optional()
          .description(
              "필터 칩 코드. 일반 조건은 같은 active roomOffer가 모두 만족해야 하고, "
                  + "NO_ARC는 Listing 정책 필터로 처리되어 propertyPolicies.arcRequired=false 매물 마커만 반환한다. "
                  + "ADDRESS_REGISTRATION은 전입신고 가능 필터다")
    };
  }

  /** 키워드 검색 API의 query parameter 문서 정의다. */
  private static ParameterDescriptor[] searchQueryParameters() {
    return new ParameterDescriptor[] {
      parameterWithName("keyword")
          .description(
              "필수 검색어(1~50자). 학교명·지역명·지하철역명 또는 별칭 일부를 보낼 수 있음. " + "예: 연세, 연세대, 서울대, 신촌, 홍대입구역"),
      parameterWithName("sort")
          .optional()
          .description(
              "정렬 프리셋(기본 DISTANCE). DISTANCE는 matchedPlace 좌표에서 가까운 순, "
                  + "PRICE_ASC는 조건에 맞는 roomOffer들의 최저 월세 낮은 순, RECOMMENDED는 추천순"),
      parameterWithName("page").optional().description("0-base 페이지 번호(기본 0)"),
      parameterWithName("size").optional().description("페이지 크기(기본 20, 최대 100)")
    };
  }

  /** 찜 등록/해제 API의 path parameter 문서 정의다. */
  private static ParameterDescriptor[] favoritePathParameters() {
    return new ParameterDescriptor[] {
      parameterWithName("listingId")
          .description("찜 등록/해제할 매물 식별자(ObjectId 24자리 hex 문자열). 공개 매물만 대상")
    };
  }

  /** 내 찜 목록 API의 query parameter 문서 정의다. */
  private static ParameterDescriptor[] favoritesQueryParameters() {
    return new ParameterDescriptor[] {
      parameterWithName("page").optional().description("0-base 페이지 번호(기본 0)"),
      parameterWithName("size").optional().description("페이지 크기(기본 20, 최대 100)")
    };
  }

  /** 지도 마커 API 응답 필드 문서 정의다. */
  private static List<FieldDescriptor> mapResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"),
        field(
            "data.markers[].listingId",
            JsonFieldType.STRING,
            "지도 마커가 가리키는 매물 식별자(ObjectId hex 문자열)"),
        field("data.markers[].lat", JsonFieldType.NUMBER, "마커를 찍을 위도(WGS84)"),
        field("data.markers[].lng", JsonFieldType.NUMBER, "마커를 찍을 경도(WGS84)"),
        field(
            "data.total",
            JsonFieldType.NUMBER,
            "현재 지도 범위와 필터에 맞는 전체 Listing 마커 수. roomOffer가 여러 개 매칭되어도 매물당 1개로 계산"),
        errorNull());
  }

  /** 찜 등록/해제 API 응답 필드 문서 정의다. */
  private static List<FieldDescriptor> favoriteToggleResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"),
        field(
            "data.favorited",
            JsonFieldType.BOOLEAN,
            "요청 처리 후 현재 사용자 기준 찜 상태. 등록 후 true, 해제 후 false"),
        field("data.favoriteCount", JsonFieldType.NUMBER, "요청 처리 후 매물의 전체 찜 수"),
        errorNull());
  }

  /** 내 찜 목록 API 응답 필드 문서 정의다. */
  private static List<FieldDescriptor> favoritesResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"),
        field("data.content[].listingId", JsonFieldType.STRING, "찜한 매물 식별자(ObjectId hex 문자열)"),
        field("data.content[].title", JsonFieldType.STRING, "매물 제목"),
        field("data.content[].type", JsonFieldType.STRING, "매물 유형"),
        field("data.content[].monthlyRent", JsonFieldType.NUMBER, "대표 방 상품의 월세(KRW)"),
        field("data.content[].deposit", JsonFieldType.NUMBER, "대표 방 상품의 보증금(KRW)"),
        field("data.content[].maintenanceFee", JsonFieldType.NUMBER, "대표 방 상품의 관리비(KRW)"),
        field("data.content[].thumbnailUrl", JsonFieldType.NULL, "썸네일 URL(없으면 null)"),
        field("data.content[].lat", JsonFieldType.NUMBER, "매물 위도(WGS84)"),
        field("data.content[].lng", JsonFieldType.NUMBER, "매물 경도(WGS84)"),
        field("data.content[].address", JsonFieldType.STRING, "카드에 표시할 주소"),
        field("data.content[].conditions", JsonFieldType.ARRAY, "대표 방 상품의 옵션 태그 목록"),
        field(
            "data.content[].favorited",
            JsonFieldType.BOOLEAN,
            "내 찜 목록 항목이므로 항상 true. 해제 성공 후에는 목록에서 빠진다"),
        field("data.content[].favoriteCount", JsonFieldType.NUMBER, "매물의 전체 찜 수"),
        field("data.content[].favoritedAt", JsonFieldType.STRING, "사용자가 이 매물을 찜한 시각(UTC ISO-8601)"),
        field("data.page.number", JsonFieldType.NUMBER, "현재 페이지 번호"),
        field("data.page.size", JsonFieldType.NUMBER, "페이지 크기"),
        field("data.page.totalElements", JsonFieldType.NUMBER, "공개 상태라 실제 응답 가능한 내 찜 매물 수"),
        field("data.page.totalPages", JsonFieldType.NUMBER, "전체 페이지 수"),
        field("data.page.hasNext", JsonFieldType.BOOLEAN, "다음 페이지 존재 여부"),
        errorNull());
  }

  /** 최근 본 매물 목록 API 응답 필드 문서 정의다. */
  private static List<FieldDescriptor> recentListingsResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"),
        field(
            "data.content[].listingId",
            JsonFieldType.STRING,
            "최근 본 매물 식별자(ObjectId hex 문자열). 같은 매물을 여러 번 봐도 한 번만 내려오며 viewedAt만 최신화됨"),
        field("data.content[].title", JsonFieldType.STRING, "최근 본 카드에 표시할 매물 제목"),
        field(
            "data.content[].type",
            JsonFieldType.STRING,
            "매물 유형. 예: GOSIWON, CO_LIVING, SHARE_HOUSE"),
        field(
            "data.content[].minMonthlyRent",
            JsonFieldType.NUMBER,
            "현재 활성 roomOffers 중 최저 월세(KRW). 일반 매물 리스트 카드와 같은 방식으로 계산"),
        field(
            "data.content[].maxMonthlyRent",
            JsonFieldType.NUMBER,
            "현재 활성 roomOffers 중 최고 월세(KRW). minMonthlyRent와 같으면 단일 가격으로 표시 가능"),
        field("data.content[].minDeposit", JsonFieldType.NUMBER, "현재 활성 roomOffers 중 최저 보증금(KRW)"),
        field("data.content[].maxDeposit", JsonFieldType.NUMBER, "현재 활성 roomOffers 중 최고 보증금(KRW)"),
        field(
            "data.content[].minMaintenanceFee",
            JsonFieldType.NUMBER,
            "현재 활성 roomOffers 중 최저 관리비(KRW)"),
        field(
            "data.content[].maxMaintenanceFee",
            JsonFieldType.NUMBER,
            "현재 활성 roomOffers 중 최고 관리비(KRW)"),
        field(
            "data.content[].minStayMonths",
            JsonFieldType.NUMBER,
            "현재 활성 roomOffers 중 가장 짧은 최소 계약 개월 수"),
        field(
            "data.content[].maxStayMonths",
            JsonFieldType.NUMBER,
            "현재 활성 roomOffers 중 가장 긴 최대 계약 개월 수"),
        field("data.content[].thumbnailUrl", JsonFieldType.NULL, "썸네일 URL(없으면 null)"),
        field("data.content[].lat", JsonFieldType.NUMBER, "매물 위도(WGS84)"),
        field("data.content[].lng", JsonFieldType.NUMBER, "매물 경도(WGS84)"),
        field("data.content[].address", JsonFieldType.STRING, "카드에 표시할 주소"),
        field("data.content[].nearestTransit", JsonFieldType.OBJECT, "가까운 교통수단 요약(없으면 null)"),
        field("data.content[].nearestTransit.type", JsonFieldType.STRING, "가까운 교통수단 유형"),
        field("data.content[].nearestTransit.name", JsonFieldType.STRING, "가까운 교통수단 이름"),
        field(
            "data.content[].nearestTransit.walkMinutes",
            JsonFieldType.NUMBER,
            "가까운 교통수단까지의 도보 시간(분)"),
        field("data.content[].conditions", JsonFieldType.ARRAY, "현재 활성 roomOffers의 조건 태그 합집합"),
        field(
            "data.content[].distanceMeters",
            JsonFieldType.NULL,
            "최근 본 목록은 지도 기준 좌표가 없으므로 항상 null. 거리 표시가 필요하면 별도 지도/검색 API를 사용"),
        field(
            "data.content[].favorited",
            JsonFieldType.BOOLEAN,
            "현재 로그인 사용자의 실제 찜 여부. true면 채운 하트, false면 빈 하트로 표시"),
        field("data.content[].favoriteCount", JsonFieldType.NUMBER, "매물의 전체 찜 수"),
        field(
            "data.content[].viewedAt",
            JsonFieldType.STRING,
            "사용자가 이 매물을 마지막으로 상세 조회한 시각(UTC ISO-8601)"),
        errorNull());
  }

  /** 목록 API 응답 필드 문서 정의다. */
  private static List<FieldDescriptor> listResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"),
        field(
            "data.content[].listingId",
            JsonFieldType.STRING,
            "카드가 가리키는 매물 식별자(ObjectId hex 문자열). 같은 매물 안에 조건을 만족하는 roomOffer가 여러 개 있어도 한 번만 내려옴"),
        field("data.content[].title", JsonFieldType.STRING, "카드에 표시할 매물 제목"),
        field(
            "data.content[].type",
            JsonFieldType.STRING,
            "매물 유형. 예: GOSIWON, CO_LIVING, SHARE_HOUSE"),
        field(
            "data.content[].minMonthlyRent",
            JsonFieldType.NUMBER,
            "조건을 통과한 active roomOffers 중 최저 월세(KRW). 프론트는 maxMonthlyRent와 함께 월세 범위를 표시"),
        field(
            "data.content[].maxMonthlyRent",
            JsonFieldType.NUMBER,
            "조건을 통과한 active roomOffers 중 최고 월세(KRW). minMonthlyRent와 같으면 단일 가격으로 표시 가능"),
        field(
            "data.content[].minDeposit",
            JsonFieldType.NUMBER,
            "조건을 통과한 active roomOffers 중 최저 보증금(KRW)"),
        field(
            "data.content[].maxDeposit",
            JsonFieldType.NUMBER,
            "조건을 통과한 active roomOffers 중 최고 보증금(KRW)"),
        field(
            "data.content[].minMaintenanceFee",
            JsonFieldType.NUMBER,
            "조건을 통과한 active roomOffers 중 최저 관리비(KRW)"),
        field(
            "data.content[].maxMaintenanceFee",
            JsonFieldType.NUMBER,
            "조건을 통과한 active roomOffers 중 최고 관리비(KRW)"),
        field(
            "data.content[].minStayMonths",
            JsonFieldType.NUMBER,
            "조건을 통과한 active roomOffers 중 가장 짧은 최소 계약 개월 수. 예: 1이면 프론트에서 1 mo~ 표시 가능"),
        field(
            "data.content[].maxStayMonths",
            JsonFieldType.NUMBER,
            "조건을 통과한 active roomOffers 중 가장 긴 최대 계약 개월 수"),
        field("data.content[].thumbnailUrl", JsonFieldType.NULL, "썸네일 URL(없으면 null)"),
        field("data.content[].lat", JsonFieldType.NUMBER, "위도(WGS84)"),
        field("data.content[].lng", JsonFieldType.NUMBER, "경도(WGS84)"),
        field("data.content[].address", JsonFieldType.STRING, "카드에 표시할 주소"),
        field("data.content[].nearestTransit", JsonFieldType.OBJECT, "가까운 교통수단 요약(없으면 null)"),
        field("data.content[].nearestTransit.type", JsonFieldType.STRING, "가까운 교통수단 유형"),
        field("data.content[].nearestTransit.name", JsonFieldType.STRING, "가까운 교통수단 이름"),
        field(
            "data.content[].nearestTransit.walkMinutes",
            JsonFieldType.NUMBER,
            "가까운 교통수단까지의 도보 시간(분)"),
        field(
            "data.content[].conditions",
            JsonFieldType.ARRAY,
            "조건을 통과한 active roomOffers의 조건 태그 합집합. 프론트의 필터 칩/카드 배지에 사용"),
        field(
            "data.content[].distanceMeters",
            JsonFieldType.NUMBER,
            "요청 bbox의 원본 중심점에서 해당 Listing까지의 직선 거리(미터). bbox 없이 조회하면 null"),
        field(
            "data.content[].favorited",
            JsonFieldType.BOOLEAN,
            "현재 사용자 찜 여부. 현재는 사용자별 찜 연동 전이라 false로 반환하며, 찜 API 연동 후 사용자별 상태로 변경 예정"),
        field("data.content[].favoriteCount", JsonFieldType.NUMBER, "찜 수"),
        field("data.page.number", JsonFieldType.NUMBER, "현재 페이지 번호"),
        field("data.page.size", JsonFieldType.NUMBER, "페이지 크기"),
        field("data.page.totalElements", JsonFieldType.NUMBER, "필터와 지도 범위에 맞는 전체 매물 카드 수"),
        field("data.page.totalPages", JsonFieldType.NUMBER, "매물 카드 기준 전체 페이지 수"),
        field("data.page.hasNext", JsonFieldType.BOOLEAN, "다음 매물 카드 페이지 존재 여부"),
        errorNull());
  }

  /** 키워드 검색 성공 응답 필드 문서 정의다. */
  private static List<FieldDescriptor> searchResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"),
        field(
            "data.matchedPlace.type",
            JsonFieldType.STRING,
            "검색어로 매칭된 장소 종류. UNIVERSITY, SUBWAY_STATION, REGION 중 하나"),
        field("data.matchedPlace.name", JsonFieldType.STRING, "프론트에 표시할 공식 장소명"),
        field("data.matchedPlace.lat", JsonFieldType.NUMBER, "지도 중심 이동에 사용할 장소 위도(WGS84)"),
        field("data.matchedPlace.lng", JsonFieldType.NUMBER, "지도 중심 이동에 사용할 장소 경도(WGS84)"),
        field(
            "data.content[].listingId",
            JsonFieldType.STRING,
            "검색 결과 카드가 가리키는 매물 식별자(ObjectId hex 문자열). 같은 매물 안에 조건을 만족하는 roomOffer가 여러 개 있어도 한 번만 내려옴"),
        field("data.content[].title", JsonFieldType.STRING, "카드에 표시할 매물 제목"),
        field(
            "data.content[].type",
            JsonFieldType.STRING,
            "매물 유형. 예: GOSIWON, CO_LIVING, SHARE_HOUSE"),
        field(
            "data.content[].minMonthlyRent",
            JsonFieldType.NUMBER,
            "검색 조건을 통과한 active roomOffers 중 최저 월세(KRW). 프론트는 maxMonthlyRent와 함께 월세 범위를 표시"),
        field(
            "data.content[].maxMonthlyRent",
            JsonFieldType.NUMBER,
            "검색 조건을 통과한 active roomOffers 중 최고 월세(KRW)"),
        field(
            "data.content[].minDeposit",
            JsonFieldType.NUMBER,
            "검색 조건을 통과한 active roomOffers 중 최저 보증금(KRW)"),
        field(
            "data.content[].maxDeposit",
            JsonFieldType.NUMBER,
            "검색 조건을 통과한 active roomOffers 중 최고 보증금(KRW)"),
        field(
            "data.content[].minMaintenanceFee",
            JsonFieldType.NUMBER,
            "검색 조건을 통과한 active roomOffers 중 최저 관리비(KRW)"),
        field(
            "data.content[].maxMaintenanceFee",
            JsonFieldType.NUMBER,
            "검색 조건을 통과한 active roomOffers 중 최고 관리비(KRW)"),
        field(
            "data.content[].minStayMonths",
            JsonFieldType.NUMBER,
            "검색 조건을 통과한 active roomOffers 중 가장 짧은 최소 계약 개월 수"),
        field(
            "data.content[].maxStayMonths",
            JsonFieldType.NUMBER,
            "검색 조건을 통과한 active roomOffers 중 가장 긴 최대 계약 개월 수"),
        field("data.content[].thumbnailUrl", JsonFieldType.NULL, "썸네일 URL(없으면 null)"),
        field("data.content[].lat", JsonFieldType.NUMBER, "매물 위도(WGS84)"),
        field("data.content[].lng", JsonFieldType.NUMBER, "매물 경도(WGS84)"),
        field("data.content[].address", JsonFieldType.STRING, "카드에 표시할 주소"),
        field("data.content[].nearestTransit", JsonFieldType.OBJECT, "가까운 교통수단 요약(없으면 null)"),
        field("data.content[].nearestTransit.type", JsonFieldType.STRING, "가까운 교통수단 유형"),
        field("data.content[].nearestTransit.name", JsonFieldType.STRING, "가까운 교통수단 이름"),
        field(
            "data.content[].nearestTransit.walkMinutes",
            JsonFieldType.NUMBER,
            "가까운 교통수단까지의 도보 시간(분)"),
        field(
            "data.content[].conditions",
            JsonFieldType.ARRAY,
            "검색 조건을 통과한 active roomOffers의 조건 태그 합집합"),
        field(
            "data.content[].distanceMeters",
            JsonFieldType.NUMBER,
            "matchedPlace 좌표에서 해당 Listing까지의 직선 거리(미터). 프론트 거리 표시용"),
        field(
            "data.content[].favorited",
            JsonFieldType.BOOLEAN,
            "현재 사용자 찜 여부. 현재는 사용자별 찜 연동 전이라 false로 반환하며, 찜 API 연동 후 사용자별 상태로 변경 예정"),
        field("data.content[].favoriteCount", JsonFieldType.NUMBER, "찜 수"),
        field("data.page.number", JsonFieldType.NUMBER, "현재 페이지 번호"),
        field("data.page.size", JsonFieldType.NUMBER, "페이지 크기"),
        field("data.page.totalElements", JsonFieldType.NUMBER, "검색 장소 3km 이내에 있는 전체 매물 카드 수"),
        field("data.page.totalPages", JsonFieldType.NUMBER, "매물 카드 기준 전체 페이지 수"),
        field("data.page.hasNext", JsonFieldType.BOOLEAN, "다음 매물 카드 페이지 존재 여부"),
        errorNull());
  }

  /** POI 매칭이 없는 키워드 검색 응답 필드 문서 정의다. */
  private static List<FieldDescriptor> searchEmptyPlaceResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"),
        field(
            "data.matchedPlace",
            JsonFieldType.NULL,
            "POI 사전에 매칭된 장소가 없다는 뜻. 프론트는 '검색된 장소가 없어요' 상태를 표시하면 됨"),
        field("data.content", JsonFieldType.ARRAY, "장소를 찾지 못했으므로 빈 배열"),
        field("data.page.number", JsonFieldType.NUMBER, "요청한 페이지 번호"),
        field("data.page.size", JsonFieldType.NUMBER, "요청한 페이지 크기"),
        field("data.page.totalElements", JsonFieldType.NUMBER, "항상 0"),
        field("data.page.totalPages", JsonFieldType.NUMBER, "항상 0"),
        field("data.page.hasNext", JsonFieldType.BOOLEAN, "항상 false"),
        errorNull());
  }

  /** 상세 API 응답 필드 문서 정의다. */
  private static List<FieldDescriptor> detailResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"),
        field("data.listingId", JsonFieldType.STRING, "매물 식별자(ObjectId hex 문자열)"),
        field("data.basicInfo.title", JsonFieldType.STRING, "상세 화면 상단에 표시할 매물 제목"),
        field(
            "data.basicInfo.type",
            JsonFieldType.STRING,
            "매물 유형. 예: GOSIWON, CO_LIVING, SHARE_HOUSE"),
        field("data.basicInfo.status", JsonFieldType.STRING, "매물 공개 상태. 상세 조회는 PUBLISHED 매물만 반환"),
        field(
            "data.summary.minMonthlyRent",
            JsonFieldType.NUMBER,
            "상세 화면 대표 월세 범위의 최저값(KRW). ACTIVE roomOffers만 집계하며 프론트는 maxMonthlyRent와 함께 가격 문구를 만든다"),
        field(
            "data.summary.maxMonthlyRent",
            JsonFieldType.NUMBER,
            "상세 화면 대표 월세 범위의 최고값(KRW). minMonthlyRent와 같으면 단일 가격으로 표시 가능"),
        field(
            "data.summary.minDeposit",
            JsonFieldType.NUMBER,
            "상세 화면 대표 보증금 범위의 최저값(KRW). ACTIVE roomOffers만 집계"),
        field(
            "data.summary.maxDeposit",
            JsonFieldType.NUMBER,
            "상세 화면 대표 보증금 범위의 최고값(KRW). minDeposit과 같으면 단일 보증금으로 표시 가능"),
        field(
            "data.summary.minMaintenanceFee",
            JsonFieldType.NUMBER,
            "상세 화면 대표 관리비 범위의 최저값(KRW). 0이면 프론트에서 No Maint. Fee처럼 표시 가능"),
        field(
            "data.summary.maxMaintenanceFee",
            JsonFieldType.NUMBER,
            "상세 화면 대표 관리비 범위의 최고값(KRW). minMaintenanceFee와 함께 관리비 범위를 표시"),
        field(
            "data.summary.minStayMonths",
            JsonFieldType.NUMBER,
            "상세 화면 대표 최소 계약기간 범위의 최저 개월 수. 예: 1이면 1 mo~ 표시 가능"),
        field("data.summary.maxStayMonths", JsonFieldType.NUMBER, "상세 화면 대표 계약기간 범위의 최고 개월 수"),
        field(
            "data.summary.activeRoomOfferCount",
            JsonFieldType.NUMBER,
            "상세 화면 Room Types 개수. 실제 노출 가능한 ACTIVE roomOffers 수와 일치"),
        field(
            "data.summary.imageCount",
            JsonFieldType.NUMBER,
            "상단 갤러리 이미지 총 개수. content.imageUrls의 길이와 동일하며 프론트의 1/N 카운터에 사용"),
        field(
            "data.summary.conditions",
            JsonFieldType.ARRAY,
            "상세 상단/특징 칩에 표시할 조건 태그. ACTIVE roomOffers의 filterTags 합집합에 ARC 불필요 매물은 NO_ARC를 파생 추가"),
        field("data.locationInfo.location.lat", JsonFieldType.NUMBER, "상세 화면 작은 지도에 사용할 위도(WGS84)"),
        field("data.locationInfo.location.lng", JsonFieldType.NUMBER, "상세 화면 작은 지도에 사용할 경도(WGS84)"),
        field("data.locationInfo.address.city", JsonFieldType.STRING, "도시 코드"),
        field("data.locationInfo.address.district", JsonFieldType.STRING, "지역구 코드"),
        field("data.locationInfo.address.fullAddress", JsonFieldType.STRING, "상세 화면에 표시할 주소"),
        field("data.locationInfo.address.detail", JsonFieldType.NULL, "상세주소(없으면 null)"),
        field("data.locationInfo.nearestTransit.type", JsonFieldType.STRING, "가까운 교통수단 유형"),
        field("data.locationInfo.nearestTransit.name", JsonFieldType.STRING, "가까운 교통수단 이름"),
        field("data.locationInfo.nearestTransit.walkMinutes", JsonFieldType.NUMBER, "도보 시간(분)"),
        field("data.locationInfo.nearbyPlacesDescription", JsonFieldType.STRING, "주변 편의시설 안내 문구"),
        field("data.locationInfo.nearbyUniversityCodes", JsonFieldType.ARRAY, "주변 학교 코드 목록"),
        field("data.propertyInfo.building.type", JsonFieldType.STRING, "건물 유형"),
        field("data.propertyInfo.building.usedFloorMin", JsonFieldType.NUMBER, "사용 층 최소값"),
        field("data.propertyInfo.building.usedFloorMax", JsonFieldType.NUMBER, "사용 층 최대값"),
        field("data.propertyInfo.building.totalFloors", JsonFieldType.NUMBER, "전체 층수"),
        field("data.propertyInfo.building.parkingAvailable", JsonFieldType.BOOLEAN, "주차 가능 여부"),
        field("data.propertyInfo.building.elevatorAvailable", JsonFieldType.BOOLEAN, "엘리베이터 여부"),
        field("data.propertyInfo.building.heatingSystem", JsonFieldType.STRING, "난방 방식"),
        field("data.propertyInfo.propertyPolicies.arcRequired", JsonFieldType.BOOLEAN, "ARC 필요 여부"),
        field(
            "data.propertyInfo.propertyPolicies.residentRegistrationAvailable",
            JsonFieldType.BOOLEAN,
            "전입신고 가능 여부"),
        field(
            "data.propertyInfo.propertyPolicies.studySuitable",
            JsonFieldType.BOOLEAN,
            "학업 목적 거주 적합 여부"),
        field(
            "data.propertyInfo.propertyPolicies.mealsProvided", JsonFieldType.BOOLEAN, "식사 제공 여부"),
        field(
            "data.propertyInfo.propertyPolicies.englishAvailable",
            JsonFieldType.BOOLEAN,
            "영어 소통 가능 여부"),
        field("data.propertyInfo.facilities.laundry", JsonFieldType.ARRAY, "세탁 시설"),
        field("data.propertyInfo.facilities.livingAmenities", JsonFieldType.ARRAY, "생활 편의시설"),
        field("data.propertyInfo.facilities.securityFeatures", JsonFieldType.ARRAY, "보안 시설"),
        field("data.propertyInfo.facilities.commonSpaces[].type", JsonFieldType.STRING, "공용공간 유형"),
        fieldWithPath("data.propertyInfo.facilities.commonSpaces[].count")
            .type(JsonFieldType.VARIES)
            .optional()
            .description("공용공간 수량(없으면 생략)"),
        field("data.propertyInfo.facilities.providedSupplies", JsonFieldType.ARRAY, "제공 물품"),
        field(
            "data.propertyInfo.featureSummary",
            JsonFieldType.ARRAY,
            "매물 전체에서 가능한 조건 태그 목록. 고시원은 활성 방 상품들의 filterTags 합집합"),
        field("data.roomOffers[].roomOfferId", JsonFieldType.STRING, "방 상품 식별자(ObjectId hex 문자열)"),
        field("data.roomOffers[].name", JsonFieldType.STRING, "방 상품명. 예: 스탠다드 1인실"),
        field(
            "data.roomOffers[].status",
            JsonFieldType.STRING,
            "방 상품 상태. 상세 응답의 roomOffers[]는 Room Types에 보여줄 ACTIVE 방 상품만 포함하므로 현재는 ACTIVE"),
        field("data.roomOffers[].rentalType", JsonFieldType.STRING, "임대 방식"),
        field("data.roomOffers[].pricing.monthlyRent", JsonFieldType.NUMBER, "월세(KRW)"),
        field("data.roomOffers[].pricing.deposit", JsonFieldType.NUMBER, "보증금(KRW)"),
        field("data.roomOffers[].pricing.maintenanceFee", JsonFieldType.NUMBER, "관리비(KRW)"),
        field("data.roomOffers[].pricing.currency", JsonFieldType.STRING, "통화"),
        field("data.roomOffers[].contract.minStayMonths", JsonFieldType.NUMBER, "최소 계약 개월"),
        field("data.roomOffers[].contract.maxStayMonths", JsonFieldType.NUMBER, "최대 계약 개월"),
        field("data.roomOffers[].contract.refundPolicy.code", JsonFieldType.STRING, "환불 정책 코드"),
        field(
            "data.roomOffers[].contract.refundPolicy.description",
            JsonFieldType.STRING,
            "환불 정책 설명"),
        field(
            "data.roomOffers[].inventory.totalCount",
            JsonFieldType.NUMBER,
            "같은 가격·조건을 가진 실제 방 전체 수"),
        field("data.roomOffers[].inventory.availableCount", JsonFieldType.NUMBER, "현재 계약 가능한 방 수"),
        field(
            "data.roomOffers[].inventory.nextAvailableFrom",
            JsonFieldType.NULL,
            "다음 입주 가능일(없으면 null)"),
        field("data.roomOffers[].genderPolicy", JsonFieldType.STRING, "성별 정책"),
        field("data.roomOffers[].features", JsonFieldType.ARRAY, "방 상품 자체 시설·형태"),
        field("data.roomOffers[].filterTags", JsonFieldType.ARRAY, "방 상품이 만족하는 매물 옵션 태그"),
        field("data.roomOffers[].roomImageUrls", JsonFieldType.ARRAY, "방 상품 이미지 URL 목록"),
        field("data.content.descriptions.ko", JsonFieldType.STRING, "한국어 상세 설명"),
        field("data.content.descriptions.en", JsonFieldType.STRING, "영어 상세 설명"),
        field("data.content.extraNotes", JsonFieldType.STRING, "자유 입력 주의사항"),
        field("data.content.imageUrls", JsonFieldType.ARRAY, "건물 공용 이미지 URL 목록"),
        field("data.content.thumbnailUrl", JsonFieldType.NULL, "대표 썸네일 URL(없으면 null)"),
        field(
            "data.reviewSummary.reviewCount",
            JsonFieldType.NUMBER,
            "리뷰 수. 리뷰 도메인 도입 전까지 0으로 반환하며, 리뷰 기능 고도화 시 실제 집계값으로 연결 예정"),
        field(
            "data.interaction.favorited",
            JsonFieldType.BOOLEAN,
            "현재 로그인 사용자의 실제 찜 여부. true면 상세 화면 하트를 채운 상태로 표시"),
        field("data.interaction.favoriteCount", JsonFieldType.NUMBER, "찜 수"),
        field("data.createdAt", JsonFieldType.STRING, "생성 시각(ISO-8601 UTC)"),
        field("data.updatedAt", JsonFieldType.STRING, "수정 시각(ISO-8601 UTC)"),
        errorNull());
  }

  /** REST Docs 필드 설명을 짧게 만들기 위한 헬퍼다. */
  private static FieldDescriptor field(String path, JsonFieldType type, String description) {
    return fieldWithPath(path).type(type).description(description);
  }

  /** 공통 실패 응답 필드 문서 정의다. */
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

  /** 공통 성공 응답의 error=null 필드를 문서화한다. */
  private static FieldDescriptor errorNull() {
    return fieldWithPath("error")
        .type(JsonFieldType.NULL)
        .optional()
        .description("성공 응답의 error는 항상 null");
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

  /** 테스트용 JWT를 Authorization 헤더 값으로 바꾼다. */
  private static String bearer(String token) {
    return "Bearer " + token;
  }
}
