package com.kohere.listing.infrastructure;

import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.document;
import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.resourceDetails;
import static com.epages.restdocs.apispec.ResourceDocumentation.resource;
import static com.kohere.docs.ApiDocsFields.errorFields;
import static com.kohere.docs.ListingDocsFields.FAVORITES_LIST_DESCRIPTION;
import static com.kohere.docs.ListingDocsFields.FAVORITES_LIST_SUMMARY;
import static com.kohere.docs.ListingDocsFields.FAVORITE_ADD_DESCRIPTION;
import static com.kohere.docs.ListingDocsFields.FAVORITE_ADD_SUMMARY;
import static com.kohere.docs.ListingDocsFields.FAVORITE_REMOVE_DESCRIPTION;
import static com.kohere.docs.ListingDocsFields.FAVORITE_REMOVE_SUMMARY;
import static com.kohere.docs.ListingDocsFields.LISTINGS_LIST_DESCRIPTION;
import static com.kohere.docs.ListingDocsFields.LISTINGS_LIST_SUMMARY;
import static com.kohere.docs.ListingDocsFields.LISTINGS_MAP_DESCRIPTION;
import static com.kohere.docs.ListingDocsFields.LISTINGS_MAP_SUMMARY;
import static com.kohere.docs.ListingDocsFields.LISTING_DETAIL_DESCRIPTION;
import static com.kohere.docs.ListingDocsFields.LISTING_DETAIL_SUMMARY;
import static com.kohere.docs.ListingDocsFields.LISTING_PLACES_DESCRIPTION;
import static com.kohere.docs.ListingDocsFields.LISTING_PLACES_SUMMARY;
import static com.kohere.docs.ListingDocsFields.RECENT_LISTINGS_DESCRIPTION;
import static com.kohere.docs.ListingDocsFields.RECENT_LISTINGS_SUMMARY;
import static com.kohere.docs.ListingDocsFields.detailResponseFields;
import static com.kohere.docs.ListingDocsFields.errorDescription;
import static com.kohere.docs.ListingDocsFields.favoritePathParameters;
import static com.kohere.docs.ListingDocsFields.favoriteToggleResponseFields;
import static com.kohere.docs.ListingDocsFields.favoritesQueryParameters;
import static com.kohere.docs.ListingDocsFields.favoritesResponseFields;
import static com.kohere.docs.ListingDocsFields.listQueryParameters;
import static com.kohere.docs.ListingDocsFields.listResponseFields;
import static com.kohere.docs.ListingDocsFields.mapQueryParameters;
import static com.kohere.docs.ListingDocsFields.mapResponseFields;
import static com.kohere.docs.ListingDocsFields.placeQueryParameters;
import static com.kohere.docs.ListingDocsFields.placeResponseFields;
import static com.kohere.docs.ListingDocsFields.recentListingsResponseFields;
import static com.kohere.docs.ListingV1DocsFields.V1_FAVORITES_LIST_DESCRIPTION;
import static com.kohere.docs.ListingV1DocsFields.V1_FAVORITES_LIST_SUMMARY;
import static com.kohere.docs.ListingV1DocsFields.V1_FAVORITE_ADD_DESCRIPTION;
import static com.kohere.docs.ListingV1DocsFields.V1_FAVORITE_ADD_SUMMARY;
import static com.kohere.docs.ListingV1DocsFields.V1_FAVORITE_REMOVE_DESCRIPTION;
import static com.kohere.docs.ListingV1DocsFields.V1_FAVORITE_REMOVE_SUMMARY;
import static com.kohere.docs.ListingV1DocsFields.V1_LISTINGS_LIST_DESCRIPTION;
import static com.kohere.docs.ListingV1DocsFields.V1_LISTINGS_LIST_SUMMARY;
import static com.kohere.docs.ListingV1DocsFields.V1_LISTINGS_MAP_DESCRIPTION;
import static com.kohere.docs.ListingV1DocsFields.V1_LISTINGS_MAP_SUMMARY;
import static com.kohere.docs.ListingV1DocsFields.V1_LISTING_DETAIL_DESCRIPTION;
import static com.kohere.docs.ListingV1DocsFields.V1_LISTING_DETAIL_SUMMARY;
import static com.kohere.docs.ListingV1DocsFields.V1_RECENT_LISTINGS_DESCRIPTION;
import static com.kohere.docs.ListingV1DocsFields.V1_RECENT_LISTINGS_SUMMARY;
import static com.kohere.docs.ListingV1DocsFields.emptyMapResponseFields;
import static com.kohere.docs.ListingV1DocsFields.emptyPageResponseFields;
import static com.kohere.docs.ListingV1DocsFields.emptyRecentListingsResponseFields;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.delete;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.post;
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
import com.kohere.docs.ApiDocsErrors;
import com.kohere.docs.ApiDocsTags;
import com.kohere.docs.ListingV1DocsFields;
import com.kohere.listing.domain.place.PlaceSearchClient;
import com.kohere.listing.domain.place.PlaceSearchResult;
import com.kohere.listing.domain.place.PlaceSearchUpstreamException;
import com.kohere.user.api.UserAccountService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
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

  /** 문서 예시로 쓰는 v4 시드 매물이다. 고시원(GOSHIWON)이고 ACTIVE 방 타입 2개를 가진다. */
  private static final String LISTING_ID = ListingTestSeeds.LISTING_ID;

  private static final String MISSING_LISTING_ID = "6858e20000000000000000ff";
  private static final String LISTINGS_COLLECTION = "listings";
  private static final String FAVORITES_COLLECTION = "favorites";
  private static final String RECENT_LISTINGS_COLLECTION = "recentListings";
  private static final String LISTING_CATALOG_COLLECTION = "listingCatalog";

  /** 시드 매물이 있는 신림 일대를 감싸는 지도 화면 bbox다. 두 번째 시드 매물(홍대)은 이 범위 밖이다. */
  private static final String SW_LAT = "37.4550";

  private static final String SW_LNG = "126.9450";
  private static final String NE_LAT = "37.4650";
  private static final String NE_LNG = "126.9600";

  // 공개 API가 잘못된 토큰을 받아도 익명 조회로 계속 동작하는지 검증할 때 사용하는 문서화용 위조 토큰.
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
  @MockitoBean private PlaceSearchClient placeSearchClient;
  @MockitoBean private UserAccountService userAccountService;

  private MockMvc mockMvc;

  /** REST Docs용 MockMvc를 만들고, 문서 예시에 사용할 v4 정본 시드(매물 2건 + 번역 사전)를 매번 초기화한다. */
  @BeforeEach
  void setUp(RestDocumentationContextProvider restDocumentation) throws Exception {
    // 사용자용 API 허용 목록 게이트(세입자·임대인만 통과)가 실제로 돈다. userType 을 스텁하지 않으면
    // mock 이 null 을 돌려줘 403 이 나고, 정작 검증하려던 경로에 닿지 못한다.
    lenient().when(userAccountService.getUserType(anyLong())).thenReturn("TENANT");
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .apply(springSecurity())
            .apply(documentationConfiguration(restDocumentation))
            .build();
    mongoTemplate.getCollection(LISTINGS_COLLECTION).deleteMany(new Document());
    mongoTemplate.getCollection(FAVORITES_COLLECTION).deleteMany(new Document());
    mongoTemplate.getCollection(RECENT_LISTINGS_COLLECTION).deleteMany(new Document());
    mongoTemplate.getCollection(LISTING_CATALOG_COLLECTION).deleteMany(new Document());
    ListingTestSeeds.seedListings(mongoTemplate, LISTINGS_COLLECTION);
    ListingTestSeeds.seedCatalog(mongoTemplate, LISTING_CATALOG_COLLECTION);
    when(userAccountService.getLanguage(1L)).thenReturn("en");
  }

  /** 매물 목록/상세 API를 호출해 Swagger 생성에 필요한 REST Docs 스니펫을 만든다. */
  @Test
  void 문서스니펫생성_매물탐색과찜API_v4응답계약과일치() throws Exception {
    String token = jwtTokenService.issueAccessToken(1L);

    // 시드 매물 2건 중 신림 고시원만 bbox·유형·예산 조건을 통과하고, 그 안에서도 조건에 맞는 방 타입 1개만 카드에 실린다.
    mockMvc
        .perform(
            get("/api/v2/listings")
                .param("swLat", SW_LAT)
                .param("swLng", SW_LNG)
                .param("neLat", NE_LAT)
                .param("neLng", NE_LNG)
                .param("maxBudget", "500000")
                .param("maxDeposit", "500000")
                .param("type", "GOSHIWON")
                .param("conditions", "FEMALE_ONLY")
                .param("conditions", "ADDRESS_REGISTRATION")
                .param("sort", "PRICE_ASC")
                .param("page", "0")
                .param("size", "20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.page.totalElements").value(1))
        .andExpect(jsonPath("$.data.content[0].listingId").value(LISTING_ID))
        .andExpect(jsonPath("$.data.content[0].title").value("Sillim Stay"))
        .andExpect(jsonPath("$.data.content[0].type.code").value("GOSHIWON"))
        .andExpect(jsonPath("$.data.content[0].type.label").value("Goshiwon"))
        .andExpect(jsonPath("$.data.content[0].rentalType.code").value("MONTHLY_RENT"))
        .andExpect(jsonPath("$.data.content[0].address.city.code").value("SEOUL"))
        .andExpect(jsonPath("$.data.content[0].address.district.code").value("GWANAK_GU"))
        .andExpect(jsonPath("$.data.content[0].address.district.label").value("Gwanak-gu"))
        .andExpect(jsonPath("$.data.content[0].building.heatingSystem").doesNotExist())
        .andExpect(jsonPath("$.data.content[0].facilities.heatingSystem[0].code").value("CENTRAL"))
        .andExpect(
            jsonPath("$.data.content[0].facilities.heatingSystem[0].label")
                .value("Central Heating"))
        .andExpect(jsonPath("$.data.content[0].facilities.commonSpaces[0].code").isString())
        .andExpect(jsonPath("$.data.content[0].facilities.commonSpaces[0].label").isString())
        .andExpect(jsonPath("$.data.content[0].roomOffers.length()").value(1))
        .andExpect(jsonPath("$.data.content[0].roomOffers[0].pricing.monthlyRent").value(380000))
        .andExpect(jsonPath("$.data.content[0].roomOffers[0].pricing.deposit").value(300000))
        .andExpect(jsonPath("$.data.content[0].roomOffers[0].contract.minStayMonths").value(1))
        .andExpect(jsonPath("$.data.content[0].roomOffers[0].contract.maxStayMonths").value(12))
        .andExpect(jsonPath("$.data.content[0].roomOffers[0].rentalType").doesNotExist())
        .andExpect(jsonPath("$.data.content[0].roomOffers[0].inventory").doesNotExist())
        .andExpect(jsonPath("$.data.content[0].propertyPolicies").doesNotExist())
        .andExpect(jsonPath("$.data.content[0].descriptions").doesNotExist())
        .andExpect(jsonPath("$.data.content[0].refundPolicy").isString())
        .andDo(
            document(
                "listings-list",
                resourceDetails()
                    .tag(ApiDocsTags.LISTINGS)
                    .summary(LISTINGS_LIST_SUMMARY)
                    .description(LISTINGS_LIST_DESCRIPTION),
                queryParameters(listQueryParameters()),
                responseFields(listResponseFields())));

    mockMvc
        .perform(
            get("/api/v2/listings/map")
                .param("swLat", SW_LAT)
                .param("swLng", SW_LNG)
                .param("neLat", NE_LAT)
                .param("neLng", NE_LNG)
                .param("maxBudget", "500000")
                .param("maxDeposit", "500000")
                .param("type", "GOSHIWON")
                .param("conditions", "FEMALE_ONLY")
                .param("conditions", "ADDRESS_REGISTRATION"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.markers[0].listingId").value(LISTING_ID))
        .andExpect(jsonPath("$.data.total").value(1))
        .andDo(
            document(
                "listings-map",
                resourceDetails()
                    .tag(ApiDocsTags.LISTINGS)
                    .summary(LISTINGS_MAP_SUMMARY)
                    .description(LISTINGS_MAP_DESCRIPTION),
                queryParameters(mapQueryParameters()),
                responseFields(mapResponseFields())));

    mockMvc
        .perform(get("/api/v2/listings/{listingId}", LISTING_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.listingId").value(LISTING_ID))
        .andExpect(jsonPath("$.data.title").value("Sillim Stay"))
        .andExpect(jsonPath("$.data.type.code").value("GOSHIWON"))
        .andExpect(jsonPath("$.data.type.label").value("Goshiwon"))
        .andExpect(jsonPath("$.data.rentalType.code").value("MONTHLY_RENT"))
        .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
        // 역명은 사용자 언어로 선택된 문자열 하나이며, v4에서는 주변 안내 문구를 함께 내려주지 않는다.
        .andExpect(jsonPath("$.data.nearestTransit.name").value("Seoul Nat'l Univ. Sta."))
        .andExpect(jsonPath("$.data.nearestTransit.nearbyPlacesDescription").doesNotExist())
        // 상세주소가 없는 매물은 키가 사라지는 것이 아니라 null로 내려간다.
        .andExpect(jsonPath("$.data.address.detail").value(nullValue()))
        .andExpect(jsonPath("$.data.refundPolicy").isString())
        .andExpect(jsonPath("$.data.description").isString())
        .andExpect(jsonPath("$.data.extraNotes").isString())
        .andExpect(jsonPath("$.data.building.heatingSystem").doesNotExist())
        .andExpect(jsonPath("$.data.facilities.heatingSystem[0].code").value("CENTRAL"))
        .andExpect(jsonPath("$.data.facilities.commonSpaces[0].code").isString())
        .andExpect(jsonPath("$.data.conditions[0].code").isString())
        .andExpect(jsonPath("$.data.conditions[0].label").isString())
        // 상세는 필터가 없으므로 ACTIVE 방 타입 2개를 모두 내려준다.
        .andExpect(jsonPath("$.data.roomOffers.length()").value(2))
        .andExpect(
            jsonPath("$.data.roomOffers[0].roomOfferId").value(ListingTestSeeds.ROOM_OFFER_ID))
        .andExpect(jsonPath("$.data.roomOffers[0].status").value("ACTIVE"))
        .andExpect(jsonPath("$.data.roomOffers[0].pricing.monthlyRent").value(380000))
        .andExpect(jsonPath("$.data.roomOffers[0].pricing.currency").value("KRW"))
        .andExpect(jsonPath("$.data.roomOffers[0].contract.minStayMonths").value(1))
        .andExpect(jsonPath("$.data.roomOffers[0].rentalType").doesNotExist())
        .andExpect(jsonPath("$.data.favorited").value(false))
        .andDo(
            document(
                "listing-detail",
                resourceDetails()
                    .tag(ApiDocsTags.LISTINGS)
                    .summary(LISTING_DETAIL_SUMMARY)
                    .description(LISTING_DETAIL_DESCRIPTION),
                pathParameters(
                    parameterWithName("listingId").description("목록/검색/마커 응답에서 받은 listingId")),
                responseFields(detailResponseFields())));

    mockMvc
        .perform(
            post("/api/v2/listings/{listingId}/favorite", LISTING_ID)
                .header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.favorited").value(true))
        .andExpect(jsonPath("$.data.favoriteCount").value(1))
        .andDo(
            document(
                "listing-favorite-add-created",
                resourceDetails()
                    .tag(ApiDocsTags.LISTINGS)
                    .summary(FAVORITE_ADD_SUMMARY)
                    .description(FAVORITE_ADD_DESCRIPTION),
                pathParameters(favoritePathParameters()),
                responseFields(favoriteToggleResponseFields())));

    mockMvc
        .perform(
            post("/api/v2/listings/{listingId}/favorite", LISTING_ID)
                .header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.favorited").value(true))
        .andExpect(jsonPath("$.data.favoriteCount").value(1))
        .andDo(
            document(
                "listing-favorite-add-existing",
                resourceDetails()
                    .tag(ApiDocsTags.LISTINGS)
                    .summary("매물 찜 등록 — 이미 찜한 상태")
                    .description(
                        FAVORITE_ADD_DESCRIPTION + " 이미 찜한 매물을 다시 호출해도 중복 저장하지 않고 현재 상태를 반환한다."),
                pathParameters(favoritePathParameters()),
                responseFields(favoriteToggleResponseFields())));

    mockMvc
        .perform(
            get("/api/v2/users/me/favorites")
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .param("page", "0")
                .param("size", "20"))
        .andExpect(status().isOk())
        // 찜한 매물은 한 건뿐이므로 두 번째 시드 매물은 목록에 없다.
        .andExpect(jsonPath("$.data.page.totalElements").value(1))
        .andExpect(jsonPath("$.data.content[0].listingId").value(LISTING_ID))
        .andExpect(jsonPath("$.data.content[0].type.code").value("GOSHIWON"))
        .andExpect(jsonPath("$.data.content[0].favorited").value(true))
        .andExpect(jsonPath("$.data.content[0].building.heatingSystem").doesNotExist())
        .andExpect(jsonPath("$.data.content[0].facilities.heatingSystem[0].code").value("CENTRAL"))
        .andExpect(jsonPath("$.data.content[0].roomOffers[0].pricing.monthlyRent").value(380000))
        .andExpect(jsonPath("$.data.content[0].descriptions").doesNotExist())
        .andDo(
            document(
                "my-favorites-list",
                resourceDetails()
                    .tag(ApiDocsTags.LISTINGS)
                    .summary(FAVORITES_LIST_SUMMARY)
                    .description(FAVORITES_LIST_DESCRIPTION),
                queryParameters(favoritesQueryParameters()),
                responseFields(favoritesResponseFields())));

    // 공개 상세 문서 예시는 비로그인 계약으로 생성했다. 최근 본 목록 문서 예시를 만들기 위해 정식 사용자로 한 번 더
    // 상세를 조회하며, 이 호출은 중복 Swagger operation을 만들지 않도록 스니펫을 생성하지 않는다.
    mockMvc
        .perform(
            get("/api/v2/listings/{listingId}", LISTING_ID)
                .header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.favorited").value(true));

    mockMvc
        .perform(
            get("/api/v2/users/me/recent-listings")
                .header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isOk())
        // 상세를 조회한 매물만 기록되므로 시드가 2건이어도 최근 본 목록은 1건이다.
        .andExpect(jsonPath("$.data.content.length()").value(1))
        .andExpect(jsonPath("$.data.content[0].listingId").value(LISTING_ID))
        .andExpect(jsonPath("$.data.content[0].type.code").value("GOSHIWON"))
        .andExpect(jsonPath("$.data.content[0].favorited").value(true))
        .andExpect(jsonPath("$.data.content[0].building.heatingSystem").doesNotExist())
        .andExpect(jsonPath("$.data.content[0].facilities.heatingSystem[0].code").value("CENTRAL"))
        .andExpect(jsonPath("$.data.content[0].roomOffers[0].pricing.monthlyRent").value(380000))
        .andExpect(
            jsonPath("$.data.content[0].nearestTransit.name").value("Seoul Nat'l Univ. Sta."))
        .andExpect(jsonPath("$.data.content[0].viewedAt").isString())
        .andDo(
            document(
                "my-recent-listings",
                resourceDetails()
                    .tag(ApiDocsTags.LISTINGS)
                    .summary(RECENT_LISTINGS_SUMMARY)
                    .description(RECENT_LISTINGS_DESCRIPTION),
                responseFields(recentListingsResponseFields())));

    mockMvc
        .perform(
            delete("/api/v2/listings/{listingId}/favorite", LISTING_ID)
                .header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.favorited").value(false))
        .andExpect(jsonPath("$.data.favoriteCount").value(0))
        .andDo(
            document(
                "listing-favorite-remove",
                resourceDetails()
                    .tag(ApiDocsTags.LISTINGS)
                    .summary(FAVORITE_REMOVE_SUMMARY)
                    .description(FAVORITE_REMOVE_DESCRIPTION),
                pathParameters(favoritePathParameters()),
                responseFields(favoriteToggleResponseFields())));
  }

  /**
   * v1 매물 조회가 저장소를 전혀 읽지 않는지 검증하고, 종료된 v1 계약의 Swagger 스니펫을 만든다.
   *
   * <p>시드 매물 2건이 있고 찜 1건·최근 본 1건까지 v2로 만들어 둔 상태에서 호출한다 — 데이터가 없어서 비는 게 아니라 v1이 저장소를 보지 않아서 빈다는 것을 이
   * 대조가 증명한다. 찜 토글 뒤 두 컬렉션 건수를 다시 세어 읽기뿐 아니라 쓰기도 없었음을 확인한다.
   */
  @Test
  void v1매물조회_시드와찜이있어도_빈결과와404이고저장소를건드리지않음() throws Exception {
    String token = jwtTokenService.issueAccessToken(1L);

    // v1이 못 보는지 확인할 실제 데이터를 v2로 만든다. 찜 1건 + (상세 조회로) 최근 본 1건.
    mockMvc
        .perform(
            post("/api/v2/listings/{listingId}/favorite", LISTING_ID)
                .header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isCreated());
    mockMvc
        .perform(
            get("/api/v2/listings/{listingId}", LISTING_ID)
                .header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isOk());
    List<Document> favoritesBefore = allDocuments(FAVORITES_COLLECTION);
    List<Document> recentBefore = allDocuments(RECENT_LISTINGS_COLLECTION);
    assertThat(favoritesBefore).hasSize(1);
    assertThat(recentBefore).hasSize(1);

    // 요청한 page·size를 그대로 돌려주는지 보려고 기본값(0·20)이 아닌 값을 보낸다.
    mockMvc
        .perform(get("/api/v1/listings").param("page", "1").param("size", "5"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content").isEmpty())
        .andExpect(jsonPath("$.data.page.number").value(1))
        .andExpect(jsonPath("$.data.page.size").value(5))
        .andExpect(jsonPath("$.data.page.totalElements").value(0))
        .andExpect(jsonPath("$.data.page.hasNext").value(false))
        .andDo(
            document(
                "v1-listings-list",
                resourceDetails()
                    .tag(ApiDocsTags.LISTINGS)
                    .summary(V1_LISTINGS_LIST_SUMMARY)
                    .description(V1_LISTINGS_LIST_DESCRIPTION)
                    .deprecated(true),
                queryParameters(ListingV1DocsFields.pageQueryParameters()),
                responseFields(emptyPageResponseFields())));

    // 시드 매물이 들어 있는 bbox인데도 마커가 없다. bbox를 아예 빼도 400이 아니라 같은 빈 결과다.
    mockMvc
        .perform(
            get("/api/v1/listings/map")
                .param("swLat", SW_LAT)
                .param("swLng", SW_LNG)
                .param("neLat", NE_LAT)
                .param("neLng", NE_LNG))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.markers").isEmpty())
        .andExpect(jsonPath("$.data.total").value(0));
    mockMvc
        .perform(get("/api/v1/listings/map"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.markers").isEmpty())
        .andExpect(jsonPath("$.data.total").value(0))
        .andDo(
            document(
                "v1-listings-map",
                resourceDetails()
                    .tag(ApiDocsTags.LISTINGS)
                    .summary(V1_LISTINGS_MAP_SUMMARY)
                    .description(V1_LISTINGS_MAP_DESCRIPTION)
                    .deprecated(true),
                responseFields(emptyMapResponseFields())));

    // 실제로 존재하는 시드 매물 ID인데도 404다.
    perform(
        get("/api/v1/listings/{listingId}", LISTING_ID),
        status().isNotFound(),
        "LISTING_NOT_FOUND",
        ApiDocsErrors.errorSnippet(
            "v1-listing-detail",
            ApiDocsTags.LISTINGS,
            V1_LISTING_DETAIL_SUMMARY,
            V1_LISTING_DETAIL_DESCRIPTION,
            true,
            favoritePathParameters(),
            "LISTING_NOT_FOUND"));

    perform(
        post("/api/v1/listings/{listingId}/favorite", LISTING_ID)
            .header(HttpHeaders.AUTHORIZATION, bearer(token)),
        status().isNotFound(),
        "LISTING_NOT_FOUND",
        ApiDocsErrors.errorSnippet(
            "v1-listing-favorite-add",
            ApiDocsTags.LISTINGS,
            V1_FAVORITE_ADD_SUMMARY,
            V1_FAVORITE_ADD_DESCRIPTION,
            true,
            favoritePathParameters(),
            "LISTING_NOT_FOUND"));

    perform(
        delete("/api/v1/listings/{listingId}/favorite", LISTING_ID)
            .header(HttpHeaders.AUTHORIZATION, bearer(token)),
        status().isNotFound(),
        "LISTING_NOT_FOUND",
        ApiDocsErrors.errorSnippet(
            "v1-listing-favorite-remove",
            ApiDocsTags.LISTINGS,
            V1_FAVORITE_REMOVE_SUMMARY,
            V1_FAVORITE_REMOVE_DESCRIPTION,
            true,
            favoritePathParameters(),
            "LISTING_NOT_FOUND"));

    // 찜해 둔 매물이 있는 사용자인데도 빈 목록이다.
    mockMvc
        .perform(
            get("/api/v1/users/me/favorites")
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .param("page", "0")
                .param("size", "20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content").isEmpty())
        .andExpect(jsonPath("$.data.page.totalElements").value(0))
        .andDo(
            document(
                "v1-my-favorites-list",
                resourceDetails()
                    .tag(ApiDocsTags.LISTINGS)
                    .summary(V1_FAVORITES_LIST_SUMMARY)
                    .description(V1_FAVORITES_LIST_DESCRIPTION)
                    .deprecated(true),
                queryParameters(ListingV1DocsFields.pageQueryParameters()),
                responseFields(emptyPageResponseFields())));

    mockMvc
        .perform(
            get("/api/v1/users/me/recent-listings")
                .header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content").isEmpty())
        .andDo(
            document(
                "v1-my-recent-listings",
                resourceDetails()
                    .tag(ApiDocsTags.LISTINGS)
                    .summary(V1_RECENT_LISTINGS_SUMMARY)
                    .description(V1_RECENT_LISTINGS_DESCRIPTION)
                    .deprecated(true),
                responseFields(emptyRecentListingsResponseFields())));

    // 인가 판정이 스텁보다 먼저다 — 토큰이 없으면 404가 아니라 401이어야 구버전 앱의 로그인 만료 처리가 그대로 동작한다.
    mockMvc
        .perform(post("/api/v1/listings/{listingId}/favorite", LISTING_ID))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    mockMvc
        .perform(get("/api/v1/users/me/favorites"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    mockMvc
        .perform(get("/api/v1/users/me/recent-listings"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));

    // 저장된 찜·조회 이력이 그대로여야 한다. 건수가 아니라 **문서 전체**를 비교한다 — 건수만 보면 나중에
    // 누군가 v1에 "기록만 하고 404" 형태를 되살렸을 때(viewedAt 갱신·favoriteCount 증가) 통과해 버린다.
    assertThat(allDocuments(FAVORITES_COLLECTION)).isEqualTo(favoritesBefore);
    assertThat(allDocuments(RECENT_LISTINGS_COLLECTION)).isEqualTo(recentBefore);
  }

  /** 네이버 장소 후보의 정상·빈 응답을 검증하고 새 장소 검색 API의 Swagger 스니펫을 생성한다. */
  @Test
  void 문서스니펫생성_네이버장소후보API_정상과빈응답을모두반환() throws Exception {
    PlaceSearchResult place =
        new PlaceSearchResult(
            "<b>경희대학교</b> 서울캠퍼스",
            "서울특별시 동대문구 회기동 1-5",
            "서울특별시 동대문구 경희대로 26",
            37.5964494,
            127.0525009);
    when(placeSearchClient.search("경희대")).thenReturn(List.of(place));

    mockMvc
        .perform(get("/api/v1/listings/places").param("keyword", "경희대"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[0].title").value("<b>경희대학교</b> 서울캠퍼스"))
        .andExpect(jsonPath("$.data.items[0].address").value("서울특별시 동대문구 회기동 1-5"))
        .andExpect(jsonPath("$.data.items[0].roadAddress").value("서울특별시 동대문구 경희대로 26"))
        .andExpect(jsonPath("$.data.items[0].lat").value(37.5964494))
        .andExpect(jsonPath("$.data.items[0].lng").value(127.0525009))
        .andDo(
            document(
                "listing-places",
                resourceDetails()
                    .tag(ApiDocsTags.LISTINGS)
                    .summary(LISTING_PLACES_SUMMARY)
                    .description(LISTING_PLACES_DESCRIPTION),
                queryParameters(placeQueryParameters()),
                responseFields(placeResponseFields())));

    when(placeSearchClient.search("없는장소")).thenReturn(List.of());
    mockMvc
        .perform(get("/api/v1/listings/places").param("keyword", "없는장소"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items").isEmpty());
  }

  /** 정식 매물 유형 GOSHIWON으로 목록·지도 필터가 정상 동작하는지 검증한다. */
  @Test
  void 유형필터_GOSHIWON_고시원시드만남고코리빙시드는제외() throws Exception {
    mockMvc
        .perform(
            get("/api/v2/listings")
                .param("type", "GOSHIWON")
                .param("page", "0")
                .param("size", "20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.page.totalElements").value(1))
        .andExpect(jsonPath("$.data.content[0].listingId").value(LISTING_ID))
        .andExpect(jsonPath("$.data.content[0].type.code").value("GOSHIWON"));

    // 유형 필터가 없으면 시드 매물 2건이 모두 보인다.
    mockMvc
        .perform(get("/api/v2/listings").param("page", "0").param("size", "20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.page.totalElements").value(2));

    mockMvc
        .perform(
            get("/api/v2/listings/map")
                .param("swLat", SW_LAT)
                .param("swLng", SW_LNG)
                .param("neLat", NE_LAT)
                .param("neLng", NE_LNG)
                .param("type", "GOSHIWON"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.markers[0].listingId").value(LISTING_ID))
        .andExpect(jsonPath("$.data.total").value(1));
  }

  /**
   * 공개 매물 조회는 인증 상태와 무관하게 계속 사용할 수 있어야 한다. 온보딩 토큰과 검증에 실패한 토큰은 정식 사용자 개인화에 사용하지 않으며, 해당 상태에서 본 상세는
   * 로그인 후 최근 본 목록으로 소급되지 않는다.
   */
  @Test
  void 공개매물조회_온보딩토큰이나위조토큰_익명으로처리하고최근본기록없음() throws Exception {
    String onboardingToken = jwtTokenService.issueOnboardingToken(1L);
    String accessToken = jwtTokenService.issueAccessToken(1L);

    mockMvc
        .perform(
            get("/api/v2/listings/{listingId}", LISTING_ID)
                .header(HttpHeaders.AUTHORIZATION, bearer(onboardingToken)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.listingId").value(LISTING_ID))
        .andExpect(jsonPath("$.data.favorited").value(false));

    mockMvc
        .perform(
            get("/api/v2/listings/{listingId}", LISTING_ID)
                .header(HttpHeaders.AUTHORIZATION, bearer(FORGED_TOKEN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.listingId").value(LISTING_ID))
        .andExpect(jsonPath("$.data.favorited").value(false));

    // 만료 토큰만은 예외다 — 익명으로 강등하지 않고 401 TOKEN_EXPIRED로 재발급을 유도한다(#181 결정 11).
    // 토큰 미전송·위조·온보딩 토큰은 위처럼 익명(200)이지만, 만료는 "재발급이 필요한 회원"이라 조용히 강등하지 않는다.
    mockMvc
        .perform(
            get("/api/v2/listings").header(HttpHeaders.AUTHORIZATION, bearer(expiredAccessToken())))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("TOKEN_EXPIRED"));

    // 위의 상세 조회들은 정식 ROLE_USER 요청이 아니므로, 같은 사용자가 온보딩을 완료한 뒤 조회해도 최근 본 목록은 비어 있어야 한다.
    mockMvc
        .perform(
            get("/api/v2/users/me/recent-listings")
                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content").isEmpty());
  }

  /** 온보딩 토큰은 공개 탐색에는 쓸 수 있지만, 사용자별 찜·최근 본 API에는 정식 ROLE_USER 권한이 없어야 한다. */
  @Test
  void 찜과최근본API_온보딩미완료토큰_403AUTH_ONBOARDING_REQUIRED() throws Exception {
    String onboardingToken = jwtTokenService.issueOnboardingToken(1L);

    mockMvc
        .perform(post("/api/v2/listings/{listingId}/favorite", LISTING_ID))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));

    mockMvc
        .perform(
            post("/api/v2/listings/{listingId}/favorite", LISTING_ID)
                .header(HttpHeaders.AUTHORIZATION, bearer(onboardingToken)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("AUTH_ONBOARDING_REQUIRED"));

    mockMvc
        .perform(
            delete("/api/v2/listings/{listingId}/favorite", LISTING_ID)
                .header(HttpHeaders.AUTHORIZATION, bearer(onboardingToken)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("AUTH_ONBOARDING_REQUIRED"));

    mockMvc
        .perform(
            get("/api/v2/users/me/favorites")
                .header(HttpHeaders.AUTHORIZATION, bearer(onboardingToken)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("AUTH_ONBOARDING_REQUIRED"));

    mockMvc
        .perform(
            get("/api/v2/users/me/recent-listings")
                .header(HttpHeaders.AUTHORIZATION, bearer(onboardingToken)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("AUTH_ONBOARDING_REQUIRED"));

    mockMvc
        .perform(get("/api/v2/users/me/recent-listings"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
  }

  /** 스펙의 "발생 가능한 에러"를 실제로 트리거해 status·error.code와 실패 응답 스니펫을 함께 만든다. */
  @Test
  void 문서스니펫생성_스펙에적힌실패조건_status와errorcode가일치() throws Exception {
    String token = jwtTokenService.issueAccessToken(1L);
    String onboardingToken = jwtTokenService.issueOnboardingToken(1L);
    String expiredToken = expiredAccessToken();

    // ===== GET /listings/places =====
    perform(
        get("/api/v1/listings/places").param("keyword", "   "),
        status().isBadRequest(),
        "INVALID_INPUT",
        "listing-places-invalid-keyword",
        LISTING_PLACES_SUMMARY,
        LISTING_PLACES_DESCRIPTION);

    when(placeSearchClient.search("경희대"))
        .thenThrow(
            new PlaceSearchUpstreamException(
                new IllegalStateException("Naver test upstream unavailable")));
    perform(
        get("/api/v1/listings/places").param("keyword", "경희대"),
        status().isBadGateway(),
        "UPSTREAM_ERROR",
        "listing-places-upstream-error",
        LISTING_PLACES_SUMMARY,
        LISTING_PLACES_DESCRIPTION);

    // ===== GET /listings =====
    perform(
        get("/api/v2/listings").param("sort", "UNKNOWN"),
        status().isBadRequest(),
        "INVALID_INPUT",
        "listings-list-invalid-sort",
        LISTINGS_LIST_SUMMARY,
        LISTINGS_LIST_DESCRIPTION);

    perform(
        get("/api/v2/listings").param("minBudget", "700000").param("maxBudget", "300000"),
        status().isBadRequest(),
        "INVALID_INPUT",
        "listings-list-invalid-budget-range",
        LISTINGS_LIST_SUMMARY,
        LISTINGS_LIST_DESCRIPTION);

    perform(
        get("/api/v2/listings").param("size", "101"),
        status().isBadRequest(),
        "INVALID_INPUT",
        "listings-list-invalid-page-size",
        LISTINGS_LIST_SUMMARY,
        LISTINGS_LIST_DESCRIPTION);

    perform(
        get("/api/v2/listings").param("swLat", "37.45"),
        status().isBadRequest(),
        "LISTING_INVALID_BBOX",
        "listings-list-invalid-bbox",
        LISTINGS_LIST_SUMMARY,
        LISTINGS_LIST_DESCRIPTION);

    perform(
        get("/api/v2/listings").param("sort", "DISTANCE"),
        status().isBadRequest(),
        "LISTING_INVALID_SORT_PARAM",
        "listings-list-invalid-distance-sort",
        LISTINGS_LIST_SUMMARY,
        LISTINGS_LIST_DESCRIPTION);

    // ===== GET /listings/map =====
    perform(
        get("/api/v2/listings/map").param("swLat", "37.45"),
        status().isBadRequest(),
        "LISTING_INVALID_BBOX",
        "listings-map-invalid-bbox",
        LISTINGS_MAP_SUMMARY,
        LISTINGS_MAP_DESCRIPTION);

    // ===== GET /listings/{listingId} =====
    perform(
        get("/api/v2/listings/{listingId}", MISSING_LISTING_ID),
        status().isNotFound(),
        "LISTING_NOT_FOUND",
        "listing-detail-not-found",
        LISTING_DETAIL_SUMMARY,
        LISTING_DETAIL_DESCRIPTION);

    // ===== POST/DELETE /listings/{listingId}/favorite =====
    perform(
        post("/api/v2/listings/{listingId}/favorite", LISTING_ID)
            .header(HttpHeaders.AUTHORIZATION, bearer(FORGED_TOKEN)),
        status().isUnauthorized(),
        "UNAUTHENTICATED",
        "listing-favorite-add-unauthenticated",
        FAVORITE_ADD_SUMMARY,
        FAVORITE_ADD_DESCRIPTION);

    perform(
        post("/api/v2/listings/{listingId}/favorite", LISTING_ID)
            .header(HttpHeaders.AUTHORIZATION, bearer(onboardingToken)),
        status().isForbidden(),
        "AUTH_ONBOARDING_REQUIRED",
        "listing-favorite-add-onboarding-required",
        FAVORITE_ADD_SUMMARY,
        FAVORITE_ADD_DESCRIPTION);

    perform(
        post("/api/v2/listings/{listingId}/favorite", LISTING_ID)
            .header(HttpHeaders.AUTHORIZATION, bearer(expiredToken)),
        status().isUnauthorized(),
        "TOKEN_EXPIRED",
        "listing-favorite-add-token-expired",
        FAVORITE_ADD_SUMMARY,
        FAVORITE_ADD_DESCRIPTION);

    perform(
        post("/api/v2/listings/{listingId}/favorite", MISSING_LISTING_ID)
            .header(HttpHeaders.AUTHORIZATION, bearer(token)),
        status().isNotFound(),
        "LISTING_NOT_FOUND",
        "listing-favorite-add-not-found",
        FAVORITE_ADD_SUMMARY,
        FAVORITE_ADD_DESCRIPTION);

    perform(
        delete("/api/v2/listings/{listingId}/favorite", MISSING_LISTING_ID)
            .header(HttpHeaders.AUTHORIZATION, bearer(token)),
        status().isNotFound(),
        "LISTING_NOT_FOUND",
        "listing-favorite-remove-not-found",
        FAVORITE_REMOVE_SUMMARY,
        FAVORITE_REMOVE_DESCRIPTION);

    perform(
        delete("/api/v2/listings/{listingId}/favorite", LISTING_ID)
            .header(HttpHeaders.AUTHORIZATION, bearer(onboardingToken)),
        status().isForbidden(),
        "AUTH_ONBOARDING_REQUIRED",
        "listing-favorite-remove-onboarding-required",
        FAVORITE_REMOVE_SUMMARY,
        FAVORITE_REMOVE_DESCRIPTION);

    // ===== GET /users/me/favorites =====
    perform(
        get("/api/v2/users/me/favorites").header(HttpHeaders.AUTHORIZATION, bearer(FORGED_TOKEN)),
        status().isUnauthorized(),
        "UNAUTHENTICATED",
        "my-favorites-list-unauthenticated",
        FAVORITES_LIST_SUMMARY,
        FAVORITES_LIST_DESCRIPTION);

    perform(
        get("/api/v2/users/me/favorites")
            .header(HttpHeaders.AUTHORIZATION, bearer(onboardingToken)),
        status().isForbidden(),
        "AUTH_ONBOARDING_REQUIRED",
        "my-favorites-list-onboarding-required",
        FAVORITES_LIST_SUMMARY,
        FAVORITES_LIST_DESCRIPTION);

    perform(
        get("/api/v2/users/me/favorites")
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .param("size", "101"),
        status().isBadRequest(),
        "INVALID_INPUT",
        "my-favorites-list-invalid-page-size",
        FAVORITES_LIST_SUMMARY,
        FAVORITES_LIST_DESCRIPTION);

    // ===== GET /users/me/recent-listings =====
    perform(
        get("/api/v2/users/me/recent-listings")
            .header(HttpHeaders.AUTHORIZATION, bearer(FORGED_TOKEN)),
        status().isUnauthorized(),
        "UNAUTHENTICATED",
        "my-recent-listings-unauthenticated",
        RECENT_LISTINGS_SUMMARY,
        RECENT_LISTINGS_DESCRIPTION);

    perform(
        get("/api/v2/users/me/recent-listings")
            .header(HttpHeaders.AUTHORIZATION, bearer(onboardingToken)),
        status().isForbidden(),
        "AUTH_ONBOARDING_REQUIRED",
        "my-recent-listings-onboarding-required",
        RECENT_LISTINGS_SUMMARY,
        RECENT_LISTINGS_DESCRIPTION);

    perform(
        get("/api/v2/users/me/recent-listings")
            .header(HttpHeaders.AUTHORIZATION, bearer(expiredToken)),
        status().isUnauthorized(),
        "TOKEN_EXPIRED",
        "my-recent-listings-token-expired",
        RECENT_LISTINGS_SUMMARY,
        RECENT_LISTINGS_DESCRIPTION);
  }

  /** 스니펫 핸들러를 직접 넘기는 형태다. path 파라미터·에러 코드 배열을 함께 실어야 하는 v1 스니펫이 쓴다. */
  private void perform(
      MockHttpServletRequestBuilder request,
      ResultMatcher expectedStatus,
      String expectedCode,
      RestDocumentationResultHandler snippet)
      throws Exception {
    mockMvc
        .perform(request)
        .andExpect(expectedStatus)
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.error.code").value(expectedCode))
        .andDo(snippet);
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
                .tag(ApiDocsTags.LISTINGS)
                .summary(summary)
                .description(description)
                .responseFields(errorFields())
                .build()));
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

  /** 컬렉션의 모든 문서를 _id 순으로 읽는다. v1 스텁이 읽기·쓰기 어느 쪽도 하지 않았음을 문서 단위로 비교할 때 쓴다. */
  private List<Document> allDocuments(String collection) {
    return mongoTemplate
        .getCollection(collection)
        .find()
        .sort(new Document("_id", 1))
        .into(new ArrayList<>());
  }

  /** 테스트용 JWT를 Authorization 헤더 값으로 바꾼다. */
  private static String bearer(String token) {
    return "Bearer " + token;
  }
}
