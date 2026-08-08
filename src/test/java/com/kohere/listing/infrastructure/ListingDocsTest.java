package com.kohere.listing.infrastructure;

import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.document;
import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.resourceDetails;
import static com.epages.restdocs.apispec.ResourceDocumentation.resource;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;
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
import com.kohere.listing.domain.place.PlaceSearchClient;
import com.kohere.listing.domain.place.PlaceSearchResult;
import com.kohere.listing.domain.place.PlaceSearchUpstreamException;
import com.kohere.listing.infrastructure.migration.ListingCatalogLabelFieldChangeUnit;
import com.kohere.listing.infrastructure.migration.ListingCatalogSeedChangeUnit;
import com.kohere.listing.infrastructure.migration.SearchPlaceSeedChangeUnit;
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
import org.springframework.restdocs.payload.FieldDescriptor;
import org.springframework.restdocs.payload.JsonFieldType;
import org.springframework.restdocs.request.ParameterDescriptor;
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

  private static final String LISTING_ID = ListingSeedFixtures.GOSHIWON_001_ID;
  private static final String MISSING_LISTING_ID = "6858e20000000000000000ff";
  private static final String LISTINGS_COLLECTION = "listings";
  private static final String FAVORITES_COLLECTION = "favorites";
  private static final String RECENT_LISTINGS_COLLECTION = "recentListings";
  private static final String SEARCH_PLACES_COLLECTION = "searchPlaces";
  private static final String LISTING_CATALOG_COLLECTION = "listingCatalog";
  private static final String LISTINGS_LIST_SUMMARY = "지도 바텀시트 매물 리스트 조회";
  private static final String LISTINGS_LIST_DESCRIPTION =
      """
      지도 화면의 바텀시트나 리스트 화면에 보여줄 매물 카드를 조회한다.

      **프론트 화면 구성**

      - 카드 제목·이미지·주소: `title`, `imageUrls[0]`, `address.fullAddress`
      - 교통·계약 정보: `nearestTransit`, `contract`
      - 가격 범위: 응답 `roomOffers[].pricing`의 최저값과 최고값으로 계산
      - 조건 배지: `conditions[].label`
      - 하트 상태: `favorited`, `favoriteCount`
      - 거리: `distanceMeters`가 있을 때만 `320m`, `1.2km`처럼 표시

      **필터와 방 타입**

      - 필터 요청 후 `content[].roomOffers[]`에는 실제 조건을 통과한 ACTIVE 방 타입만 포함된다.
      - `conditions`는 ACTIVE 방 타입의 `filterTags`와 `NO_ARC` 같은 매물 정책 조건을 합친 결과다.

      **다국어와 code/label 사용법**

      - 화면에는 `label`을 표시한다.
      - 필터 요청과 내부 비교에는 `code`를 사용한다.
      - 제목·주소·역명·방 이름·설명은 서버가 사용자 언어 문자열 하나로 선택해서 전달한다.
      - 인증 없이 사용할 수 있으며 비로그인·온보딩 미완료 사용자는 영어가 기본이다.
      - 온보딩을 완료한 로그인 사용자는 계정 언어를 사용한다.

      카드를 선택하면 같은 `listingId`로 상세 API를 호출하고 지도 마커의 선택 상태도 맞추면 된다.
      """;
  private static final String LISTINGS_MAP_SUMMARY = "지도 마커 조회";
  private static final String LISTINGS_MAP_DESCRIPTION =
      """
      현재 지도 영역에 표시할 매물 마커 좌표만 빠르게 조회한다.

      **프론트 사용 방법**

      - 지도 SDK에서 현재 viewport의 `swLat`, `swLng`, `neLat`, `neLng`를 구해 네 값을 모두 보낸다.
      - `markers[].lat`, `markers[].lng`로 지도 마커를 렌더링한다.
      - `markers[].listingId`로 마커 선택, 목록 카드 선택, 상세 진입을 서로 연결한다.
      - 가격·이미지·주소가 필요한 바텀시트는 `GET /api/v1/listings`를 같은 필터로 함께 호출한다.

      인증 없이 사용할 수 있다. 지도 마커 응답에는 화면 표시용 번역 문구가 없으며 좌표와 식별자만 포함된다.
      """;
  private static final String LISTINGS_SEARCH_SUMMARY = "키워드 장소 검색과 주변 매물 조회";
  private static final String LISTINGS_SEARCH_DESCRIPTION =
      """
      학교명·지역명·지하철역명으로 장소를 찾고, 그 장소 주변의 매물을 함께 조회한다.

      **프론트 사용 방법**

      - `matchedPlace`가 있으면 해당 `lat/lng`로 지도 카메라를 이동한다.
      - `content[]`는 검색 결과 매물 카드 목록으로 표시한다.
      - `content[].distanceMeters`는 검색 장소에서 매물까지의 직선거리 라벨에 사용한다.
      - `conditions[].label`은 카드 조건 배지에 표시한다.
      - `roomOffers[]`로 가격 범위와 노출할 방 타입을 계산한다.

      **빈 결과 구분**

      - `matchedPlace=null`, `content=[]`: 검색어와 일치하는 장소가 없음
      - `matchedPlace` 존재, `content=[]`: 장소는 찾았지만 주변 매물이 없음

      인증 없이 사용할 수 있다. 매물의 다국어 문자열과 `code/label` 사용 규칙은 매물 목록 API와 동일하며, 비로그인·온보딩 미완료 사용자는 영어가
      기본이다.
      """;
  private static final String LISTING_PLACES_SUMMARY = "네이버 장소 후보 검색";
  private static final String LISTING_PLACES_DESCRIPTION =
      """
      지도 검색창의 `keyword`로 네이버 지역 검색을 호출하고 정확도순 장소 후보를 최대 5개 반환한다.

      **프론트 사용 방법**

      - 검색 결과에는 `items[].title`, `address`, `roadAddress`를 표시한다.
      - 사용자가 후보를 선택하면 `items[].lat/lng`로 지도 카메라를 이동한다.
      - 카메라 이동 후 지도 SDK에서 bounds를 계산해 매물 목록 API와 지도 마커 API를 호출한다.

      인증 없이 사용할 수 있다. 이 API는 장소 후보만 반환하며 MongoDB의 실제 매물은 조회하지 않는다.
      """;
  private static final String LISTING_DETAIL_SUMMARY = "매물 상세 조회";
  private static final String LISTING_DETAIL_DESCRIPTION =
      """
      목록 카드나 지도 마커에서 매물을 선택한 뒤 상세 화면 전체를 구성할 때 사용한다.

      **인증에 따른 동작**

      - 인증 없이 조회할 수 있다. 비로그인·온보딩 미완료 사용자는 `favorited=false`이며 최근 본 기록을 남기지 않는다.
      - 온보딩을 완료한 로그인 사용자는 실제 찜 상태와 계정 언어가 적용되고, 조회한 매물이 최근 본 목록에 기록된다.
      - 로그인 전에 조회한 매물은 로그인 후 최근 본 목록으로 소급해 옮기지 않는다.

      **상세 화면 구성**

      - 상단 제목·하트: `title`, `favorited`, `favoriteCount`
      - 사진 갤러리: `imageUrls`, `roomOffers[].roomImageUrls`
      - 방 타입·가격·재고: `roomOffers[]`, `pricing`, `inventory`
      - 계약기간: `contract`
      - 주소·지도: `address`, `location`
      - 교통 정보: `nearestTransit`
      - 조건 배지: `conditions[].label`
      - 건물·정책·시설: `building`, `propertyPolicies`, `facilities`

      난방 정보는 `building`이 아니라 `facilities.heatingSystem[]`에서 읽는다. `roomOffers[]`에는 상세 화면의 Room Types에 표시할 ACTIVE 방 타입이 들어 있다.

      **다국어와 code/label 사용법**

      - `type`, `rentalType`, `genderPolicy`, 교통수단, 시설, `conditions`, `filterTags`는 `{code,label}` 구조다.
      - 화면에는 `label`을 표시하고 필터 요청·내부 비교에는 `code`를 사용한다.
      - `title`, 주소, 역명, 방 이름, 환불 설명, `descriptions.description`은 서버가 사용자 언어로 선택한 문자열 하나다.
      - 프론트는 `ko/en`을 직접 선택하거나 MongoDB 번역 구조를 알 필요가 없다.
      """;
  private static final String FAVORITE_ADD_SUMMARY = "매물 찜 등록";
  private static final String FAVORITE_ADD_DESCRIPTION =
      """
      온보딩을 완료한 로그인 사용자(ROLE_USER)가 공개 매물을 찜할 때 호출한다.

      **응답 처리**

      - 처음 찜한 경우: `201 Created`
      - 이미 찜한 매물을 다시 요청한 경우: `200 OK`
      - 두 응답 모두 `favorited=true`와 변경 후 `favoriteCount`를 반환한다.

      프론트는 상태코드와 관계없이 응답의 `favorited`, `favoriteCount`로 하트 상태와 숫자를 갱신하면 된다.
      """;
  private static final String FAVORITE_REMOVE_SUMMARY = "매물 찜 해제";
  private static final String FAVORITE_REMOVE_DESCRIPTION =
      """
      온보딩을 완료한 로그인 사용자(ROLE_USER)가 매물 찜을 해제할 때 호출한다.

      - 이미 찜하지 않은 상태에서 다시 호출해도 에러가 아니다.
      - 성공 응답은 `200 OK`, `favorited=false`와 변경 후 `favoriteCount`다.
      - 프론트는 응답값으로 카드와 상세 화면의 하트 상태·찜 수를 갱신하면 된다.
      """;
  private static final String FAVORITES_LIST_SUMMARY = "내 찜한 매물 목록";
  private static final String FAVORITES_LIST_DESCRIPTION =
      """
      온보딩을 완료한 로그인 사용자가 마이페이지의 찜한 매물 목록을 페이지 단위로 조회한다.

      **프론트 사용 방법**

      - `content[]`는 일반 매물 카드와 거의 같은 구조다.
      - 목록의 `favorited`는 항상 `true`다.
      - `favoritedAt`은 사용자가 찜한 시각으로, 최신순 표시나 보조 문구에 사용할 수 있다.
      - 매물 표시 문구와 `{code,label}`은 현재 사용자 언어로 반환된다.

      찜 해제 후 목록을 다시 조회하거나, 클라이언트에서 같은 `listingId` 항목을 즉시 제거하면 된다.
      """;
  private static final String RECENT_LISTINGS_SUMMARY = "최근 본 매물 목록";
  private static final String RECENT_LISTINGS_DESCRIPTION =
      """
      마이페이지나 홈의 최근 본 매물 영역에 사용할 최대 10개 매물을 조회한다.

      **프론트 사용 방법**

      - 별도 요청 파라미터는 없다.
      - 온보딩 완료 사용자가 매물 상세 API를 호출하면 최근 본 기록이 서버에서 자동 갱신된다.
      - 비로그인·온보딩 미완료 상태의 조회 기록은 저장하거나 로그인 후 소급 이전하지 않는다.
      - `content[]`는 일반 매물 카드와 거의 같은 구조다.
      - `viewedAt`은 마지막으로 상세 화면을 본 시각이다.
      - `favorited`로 현재 하트 상태를 그린다.
      - 매물 표시 문구와 `{code,label}`은 현재 사용자 언어로 반환된다.

      오래되었거나 더 이상 공개 상태가 아닌 매물은 응답에 포함되지 않는다.
      """;

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
  @Autowired private ListingRepository listingRepository;
  @MockitoBean private PlaceSearchClient placeSearchClient;
  @MockitoBean private UserAccountService userAccountService;

  private MockMvc mockMvc;

  /** REST Docs용 MockMvc를 만들고, 문서 예시에 사용할 매물 seed 데이터를 매번 초기화한다. */
  @BeforeEach
  void setUp(RestDocumentationContextProvider restDocumentation) throws Exception {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .apply(springSecurity())
            .apply(documentationConfiguration(restDocumentation))
            .build();
    mongoTemplate.getCollection(LISTINGS_COLLECTION).deleteMany(new Document());
    mongoTemplate.getCollection(FAVORITES_COLLECTION).deleteMany(new Document());
    mongoTemplate.getCollection(RECENT_LISTINGS_COLLECTION).deleteMany(new Document());
    mongoTemplate.getCollection(SEARCH_PLACES_COLLECTION).deleteMany(new Document());
    mongoTemplate.getCollection(LISTING_CATALOG_COLLECTION).deleteMany(new Document());
    new ListingSeedRunner(listingRepository).run(null);
    new ListingCatalogSeedChangeUnit().execution(mongoTemplate);
    new ListingCatalogLabelFieldChangeUnit().execution(mongoTemplate);
    new SearchPlaceSeedChangeUnit().execution(mongoTemplate);
    when(userAccountService.getLanguage(1L)).thenReturn("en");
  }

  /** 매물 목록/상세 API를 호출해 Swagger 생성에 필요한 REST Docs 스니펫을 만든다. */
  @Test
  void generatesListingSnippets() throws Exception {
    String token = jwtTokenService.issueAccessToken(1L);

    mockMvc
        .perform(
            get("/api/v1/listings")
                .param("swLat", "37.45920")
                .param("swLng", "126.95120")
                .param("neLat", "37.45946")
                .param("neLng", "126.95141")
                .param("maxBudget", "500000")
                .param("maxDeposit", "500000")
                .param("type", "GOSHIWON")
                .param("conditions", "FEMALE_ONLY")
                .param("conditions", "ADDRESS_REGISTRATION")
                .param("conditions", "NO_ARC")
                .param("sort", "PRICE_ASC")
                .param("page", "0")
                .param("size", "20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[0].listingId").value(LISTING_ID))
        .andExpect(jsonPath("$.data.content[0].type.code").value("GOSHIWON"))
        .andExpect(jsonPath("$.data.content[0].type.label").value("Goshiwon"))
        .andExpect(jsonPath("$.data.content[0].rentalType.code").value("MONTHLY_RENT"))
        .andExpect(jsonPath("$.data.content[0].building.heatingSystem").doesNotExist())
        .andExpect(jsonPath("$.data.content[0].facilities.heatingSystem[0].code").value("CENTRAL"))
        .andExpect(
            jsonPath("$.data.content[0].facilities.heatingSystem[0].label")
                .value("Central Heating"))
        .andExpect(jsonPath("$.data.content[0].roomOffers[0].pricing.monthlyRent").value(300000))
        .andExpect(jsonPath("$.data.content[0].roomOffers[0].rentalType").doesNotExist())
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
                .param("keyword", "서울대")
                .param("sort", "DISTANCE")
                .param("page", "0")
                .param("size", "20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.matchedPlace.name").value("서울대학교"))
        .andExpect(jsonPath("$.data.content[0].listingId").value(LISTING_ID))
        .andExpect(jsonPath("$.data.content[0].rentalType.code").value("MONTHLY_RENT"))
        .andExpect(jsonPath("$.data.content[0].building.heatingSystem").doesNotExist())
        .andExpect(jsonPath("$.data.content[0].facilities.heatingSystem[0].code").value("CENTRAL"))
        .andExpect(jsonPath("$.data.content[0].roomOffers[0].pricing.monthlyRent").value(300000))
        .andExpect(jsonPath("$.data.content[0].roomOffers[0].rentalType").doesNotExist())
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
                .param("swLat", "37.45920")
                .param("swLng", "126.95120")
                .param("neLat", "37.45946")
                .param("neLng", "126.95141")
                .param("maxBudget", "500000")
                .param("maxDeposit", "500000")
                .param("type", "GOSHIWON")
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
        .perform(get("/api/v1/listings/{listingId}", LISTING_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.listingId").value(LISTING_ID))
        .andExpect(jsonPath("$.data.title").value("Goshiwon 001"))
        .andExpect(jsonPath("$.data.type.code").value("GOSHIWON"))
        .andExpect(jsonPath("$.data.type.label").value("Goshiwon"))
        .andExpect(jsonPath("$.data.rentalType.code").value("MONTHLY_RENT"))
        .andExpect(jsonPath("$.data.building.heatingSystem").doesNotExist())
        .andExpect(jsonPath("$.data.facilities.heatingSystem[0].code").value("CENTRAL"))
        .andExpect(jsonPath("$.data.conditions[0].code").isString())
        .andExpect(jsonPath("$.data.conditions[0].label").isString())
        .andExpect(jsonPath("$.data.roomOffers[0].pricing.monthlyRent").value(300000))
        .andExpect(jsonPath("$.data.roomOffers[0].rentalType").doesNotExist())
        .andExpect(jsonPath("$.data.favorited").value(false))
        .andDo(
            document(
                "listing-detail",
                resourceDetails()
                    .summary(LISTING_DETAIL_SUMMARY)
                    .description(LISTING_DETAIL_DESCRIPTION),
                pathParameters(
                    parameterWithName("listingId").description("목록/검색/마커 응답에서 받은 listingId")),
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
        .andExpect(jsonPath("$.data.content[0].type.code").value("GOSHIWON"))
        .andExpect(jsonPath("$.data.content[0].favorited").value(true))
        .andExpect(jsonPath("$.data.content[0].building.heatingSystem").doesNotExist())
        .andExpect(jsonPath("$.data.content[0].facilities.heatingSystem[0].code").value("CENTRAL"))
        .andExpect(jsonPath("$.data.content[0].roomOffers[0].pricing.monthlyRent").value(300000))
        .andDo(
            document(
                "my-favorites-list",
                resourceDetails()
                    .summary(FAVORITES_LIST_SUMMARY)
                    .description(FAVORITES_LIST_DESCRIPTION),
                queryParameters(favoritesQueryParameters()),
                responseFields(favoritesResponseFields())));

    // 공개 상세 문서 예시는 비로그인 계약으로 생성했다. 최근 본 목록 문서 예시를 만들기 위해 정식 사용자로 한 번 더
    // 상세를 조회하며, 이 호출은 중복 Swagger operation을 만들지 않도록 스니펫을 생성하지 않는다.
    mockMvc
        .perform(
            get("/api/v1/listings/{listingId}", LISTING_ID)
                .header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.favorited").value(true));

    mockMvc
        .perform(
            get("/api/v1/users/me/recent-listings")
                .header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[0].listingId").value(LISTING_ID))
        .andExpect(jsonPath("$.data.content[0].type.code").value("GOSHIWON"))
        .andExpect(jsonPath("$.data.content[0].favorited").value(true))
        .andExpect(jsonPath("$.data.content[0].building.heatingSystem").doesNotExist())
        .andExpect(jsonPath("$.data.content[0].facilities.heatingSystem[0].code").value("CENTRAL"))
        .andExpect(jsonPath("$.data.content[0].roomOffers[0].pricing.monthlyRent").value(300000))
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

  /** 네이버 장소 후보의 정상·빈 응답을 검증하고 새 장소 검색 API의 Swagger 스니펫을 생성한다. */
  @Test
  void generatesListingPlaceSnippets() throws Exception {
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
  void filtersListingsByCanonicalGoshiwonType() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/listings")
                .param("type", "GOSHIWON")
                .param("page", "0")
                .param("size", "20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[0].listingId").value(LISTING_ID))
        .andExpect(jsonPath("$.data.content[0].type.code").value("GOSHIWON"));

    mockMvc
        .perform(
            get("/api/v1/listings/map")
                .param("swLat", "37.45920")
                .param("swLng", "126.95120")
                .param("neLat", "37.45946")
                .param("neLng", "126.95141")
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
  void publicListingReadsTreatNonUserAuthenticationAsAnonymous() throws Exception {
    String onboardingToken = jwtTokenService.issueOnboardingToken(1L);
    String accessToken = jwtTokenService.issueAccessToken(1L);

    mockMvc
        .perform(
            get("/api/v1/listings/{listingId}", LISTING_ID)
                .header(HttpHeaders.AUTHORIZATION, bearer(onboardingToken)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.listingId").value(LISTING_ID))
        .andExpect(jsonPath("$.data.favorited").value(false));

    mockMvc
        .perform(
            get("/api/v1/listings/{listingId}", LISTING_ID)
                .header(HttpHeaders.AUTHORIZATION, bearer(FORGED_TOKEN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.listingId").value(LISTING_ID))
        .andExpect(jsonPath("$.data.favorited").value(false));

    // 만료 토큰만은 예외다 — 익명으로 강등하지 않고 401 TOKEN_EXPIRED로 재발급을 유도한다(#181 결정 11).
    // 토큰 미전송·위조·온보딩 토큰은 위처럼 익명(200)이지만, 만료는 "재발급이 필요한 회원"이라 조용히 강등하지 않는다.
    mockMvc
        .perform(
            get("/api/v1/listings").header(HttpHeaders.AUTHORIZATION, bearer(expiredAccessToken())))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("TOKEN_EXPIRED"));

    // 위의 상세 조회들은 정식 ROLE_USER 요청이 아니므로, 같은 사용자가 온보딩을 완료한 뒤 조회해도 최근 본 목록은 비어 있어야 한다.
    mockMvc
        .perform(
            get("/api/v1/users/me/recent-listings")
                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content").isEmpty());
  }

  /** 온보딩 토큰은 공개 탐색에는 쓸 수 있지만, 사용자별 찜·최근 본 API에는 정식 ROLE_USER 권한이 없어야 한다. */
  @Test
  void personalListingFeaturesRequireCompletedOnboarding() throws Exception {
    String onboardingToken = jwtTokenService.issueOnboardingToken(1L);

    mockMvc
        .perform(post("/api/v1/listings/{listingId}/favorite", LISTING_ID))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));

    mockMvc
        .perform(
            post("/api/v1/listings/{listingId}/favorite", LISTING_ID)
                .header(HttpHeaders.AUTHORIZATION, bearer(onboardingToken)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("AUTH_ONBOARDING_REQUIRED"));

    mockMvc
        .perform(
            delete("/api/v1/listings/{listingId}/favorite", LISTING_ID)
                .header(HttpHeaders.AUTHORIZATION, bearer(onboardingToken)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("AUTH_ONBOARDING_REQUIRED"));

    mockMvc
        .perform(
            get("/api/v1/users/me/favorites")
                .header(HttpHeaders.AUTHORIZATION, bearer(onboardingToken)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("AUTH_ONBOARDING_REQUIRED"));

    mockMvc
        .perform(
            get("/api/v1/users/me/recent-listings")
                .header(HttpHeaders.AUTHORIZATION, bearer(onboardingToken)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("AUTH_ONBOARDING_REQUIRED"));

    mockMvc
        .perform(get("/api/v1/users/me/recent-listings"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
  }

  /** 스펙의 "발생 가능한 에러"를 실제로 트리거해 status·error.code와 실패 응답 스니펫을 함께 만든다. */
  @Test
  void generatesListingErrorSnippets() throws Exception {
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
        get("/api/v1/listings").param("sort", "UNKNOWN"),
        status().isBadRequest(),
        "INVALID_INPUT",
        "listings-list-invalid-sort",
        LISTINGS_LIST_SUMMARY,
        LISTINGS_LIST_DESCRIPTION);

    perform(
        get("/api/v1/listings").param("minBudget", "700000").param("maxBudget", "300000"),
        status().isBadRequest(),
        "INVALID_INPUT",
        "listings-list-invalid-budget-range",
        LISTINGS_LIST_SUMMARY,
        LISTINGS_LIST_DESCRIPTION);

    perform(
        get("/api/v1/listings").param("size", "101"),
        status().isBadRequest(),
        "INVALID_INPUT",
        "listings-list-invalid-page-size",
        LISTINGS_LIST_SUMMARY,
        LISTINGS_LIST_DESCRIPTION);

    perform(
        get("/api/v1/listings").param("swLat", "37.45"),
        status().isBadRequest(),
        "LISTING_INVALID_BBOX",
        "listings-list-invalid-bbox",
        LISTINGS_LIST_SUMMARY,
        LISTINGS_LIST_DESCRIPTION);

    perform(
        get("/api/v1/listings").param("sort", "DISTANCE"),
        status().isBadRequest(),
        "LISTING_INVALID_SORT_PARAM",
        "listings-list-invalid-distance-sort",
        LISTINGS_LIST_SUMMARY,
        LISTINGS_LIST_DESCRIPTION);

    // ===== GET /listings/search =====
    perform(
        get("/api/v1/listings/search"),
        status().isBadRequest(),
        "INVALID_INPUT",
        "listings-search-invalid-keyword-missing",
        LISTINGS_SEARCH_SUMMARY,
        LISTINGS_SEARCH_DESCRIPTION);

    perform(
        get("/api/v1/listings/search").param("keyword", "   "),
        status().isBadRequest(),
        "INVALID_INPUT",
        "listings-search-invalid-keyword-blank",
        LISTINGS_SEARCH_SUMMARY,
        LISTINGS_SEARCH_DESCRIPTION);

    perform(
        get("/api/v1/listings/search").param("keyword", "가".repeat(51)),
        status().isBadRequest(),
        "INVALID_INPUT",
        "listings-search-invalid-keyword-too-long",
        LISTINGS_SEARCH_SUMMARY,
        LISTINGS_SEARCH_DESCRIPTION);

    perform(
        get("/api/v1/listings/search").param("keyword", "서울대").param("size", "101"),
        status().isBadRequest(),
        "INVALID_INPUT",
        "listings-search-invalid-page-size",
        LISTINGS_SEARCH_SUMMARY,
        LISTINGS_SEARCH_DESCRIPTION);

    // ===== GET /listings/map =====
    perform(
        get("/api/v1/listings/map").param("swLat", "37.45"),
        status().isBadRequest(),
        "LISTING_INVALID_BBOX",
        "listings-map-invalid-bbox",
        LISTINGS_MAP_SUMMARY,
        LISTINGS_MAP_DESCRIPTION);

    // ===== GET /listings/{listingId} =====
    perform(
        get("/api/v1/listings/{listingId}", MISSING_LISTING_ID),
        status().isNotFound(),
        "LISTING_NOT_FOUND",
        "listing-detail-not-found",
        LISTING_DETAIL_SUMMARY,
        LISTING_DETAIL_DESCRIPTION);

    // ===== POST/DELETE /listings/{listingId}/favorite =====
    perform(
        post("/api/v1/listings/{listingId}/favorite", LISTING_ID)
            .header(HttpHeaders.AUTHORIZATION, bearer(FORGED_TOKEN)),
        status().isUnauthorized(),
        "UNAUTHENTICATED",
        "listing-favorite-add-unauthenticated",
        FAVORITE_ADD_SUMMARY,
        FAVORITE_ADD_DESCRIPTION);

    perform(
        post("/api/v1/listings/{listingId}/favorite", LISTING_ID)
            .header(HttpHeaders.AUTHORIZATION, bearer(onboardingToken)),
        status().isForbidden(),
        "AUTH_ONBOARDING_REQUIRED",
        "listing-favorite-add-onboarding-required",
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

    perform(
        delete("/api/v1/listings/{listingId}/favorite", LISTING_ID)
            .header(HttpHeaders.AUTHORIZATION, bearer(onboardingToken)),
        status().isForbidden(),
        "AUTH_ONBOARDING_REQUIRED",
        "listing-favorite-remove-onboarding-required",
        FAVORITE_REMOVE_SUMMARY,
        FAVORITE_REMOVE_DESCRIPTION);

    // ===== GET /users/me/favorites =====
    perform(
        get("/api/v1/users/me/favorites").header(HttpHeaders.AUTHORIZATION, bearer(FORGED_TOKEN)),
        status().isUnauthorized(),
        "UNAUTHENTICATED",
        "my-favorites-list-unauthenticated",
        FAVORITES_LIST_SUMMARY,
        FAVORITES_LIST_DESCRIPTION);

    perform(
        get("/api/v1/users/me/favorites")
            .header(HttpHeaders.AUTHORIZATION, bearer(onboardingToken)),
        status().isForbidden(),
        "AUTH_ONBOARDING_REQUIRED",
        "my-favorites-list-onboarding-required",
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
        get("/api/v1/users/me/recent-listings")
            .header(HttpHeaders.AUTHORIZATION, bearer(FORGED_TOKEN)),
        status().isUnauthorized(),
        "UNAUTHENTICATED",
        "my-recent-listings-unauthenticated",
        RECENT_LISTINGS_SUMMARY,
        RECENT_LISTINGS_DESCRIPTION);

    perform(
        get("/api/v1/users/me/recent-listings")
            .header(HttpHeaders.AUTHORIZATION, bearer(onboardingToken)),
        status().isForbidden(),
        "AUTH_ONBOARDING_REQUIRED",
        "my-recent-listings-onboarding-required",
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

  /** 네이버 장소 후보 API가 프론트에서 받는 유일한 검색 조건을 문서화한다. */
  private static ParameterDescriptor[] placeQueryParameters() {
    return new ParameterDescriptor[] {
      parameterWithName("keyword")
          .description("지도 검색창 입력값(1~50자). 서버가 trim한 뒤 네이버 지역 검색 API의 query로 전달")
    };
  }

  /** 목록 API의 query parameter 문서 정의다. */
  private static ParameterDescriptor[] listQueryParameters() {
    return new ParameterDescriptor[] {
      parameterWithName("swLat")
          .optional()
          .description("현재 지도 화면의 남서쪽 위도. 지도 영역 기준으로 목록을 갱신할 때 네 좌표를 모두 보낸다"),
      parameterWithName("swLng")
          .optional()
          .description("현재 지도 화면의 남서쪽 경도. swLat와 함께 지도 viewport의 왼쪽 아래 좌표"),
      parameterWithName("neLat").optional().description("현재 지도 화면의 북동쪽 위도. swLat보다 큰 값이어야 함"),
      parameterWithName("neLng").optional().description("현재 지도 화면의 북동쪽 경도. swLng보다 큰 값이어야 함"),
      parameterWithName("minBudget")
          .optional()
          .description("월세 최소값(KRW). 이 범위에 맞는 방 타입이 있는 매물만 보이고, 응답 roomOffers[]도 조건에 맞는 방 타입만 포함"),
      parameterWithName("maxBudget")
          .optional()
          .description("월세 최대값(KRW). 예산 필터의 상한값으로 사용하며, 카드 가격은 응답 roomOffers[].pricing으로 계산"),
      parameterWithName("minDeposit").optional().description("보증금 최소값(KRW). 보증금 필터 슬라이더/입력값의 하한"),
      parameterWithName("maxDeposit").optional().description("보증금 최대값(KRW). 보증금 필터 슬라이더/입력값의 상한"),
      parameterWithName("type")
          .optional()
          .description("매물 유형 필터 칩. 예: GOSHIWON, CO_LIVING, SHARE_HOUSE, OTHER"),
      parameterWithName("conditions")
          .optional()
          .description(
              "옵션 필터 칩 코드. FEMALE_ONLY, PRIVATE_BATH, MOVE_IN_NOW, ADDRESS_REGISTRATION, NO_ARC 등을 반복 파라미터나 콤마로 보낼 수 있음. "
                  + "MOVE_IN_NOW는 바로 계약 가능한 방 타입만, NO_ARC는 ARC 없이 가능한 매물만 보여줄 때 사용"),
      parameterWithName("sort")
          .optional()
          .description(
              "정렬 방식. RECOMMENDED는 기본 추천순, PRICE_ASC는 조건에 맞는 방 타입 중 가장 낮은 월세순, DISTANCE는 현재 지도 중심에서 가까운 순. "
                  + "DISTANCE를 쓰려면 지도 좌표 네 값을 함께 보내야 함"),
      parameterWithName("page").optional().description("0부터 시작하는 페이지 번호. 무한스크롤이면 다음 페이지 요청에 사용"),
      parameterWithName("size").optional().description("한 번에 가져올 매물 수. 기본 20, 최대 100")
    };
  }

  /** 네이버 원본 메타데이터를 제외하고 프론트에 공개하는 장소 후보 필드만 문서화한다. */
  private static List<FieldDescriptor> placeResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"),
        field("data.items[].title", JsonFieldType.STRING, "검색어 강조 <b> 태그를 포함할 수 있는 네이버 장소명"),
        field("data.items[].address", JsonFieldType.STRING, "지번 주소. 네이버가 제공하지 않으면 빈 문자열"),
        field("data.items[].roadAddress", JsonFieldType.STRING, "도로명 주소. 네이버가 제공하지 않으면 빈 문자열"),
        field("data.items[].lat", JsonFieldType.NUMBER, "선택 시 지도 중심 이동에 사용할 WGS84 위도"),
        field("data.items[].lng", JsonFieldType.NUMBER, "선택 시 지도 중심 이동에 사용할 WGS84 경도"),
        errorNull());
  }

  /** 지도 마커 API의 query parameter 문서 정의다. */
  private static ParameterDescriptor[] mapQueryParameters() {
    return new ParameterDescriptor[] {
      parameterWithName("swLat").description("현재 지도 화면의 남서쪽 위도. 지도 마커 조회는 bbox 네 값이 모두 필수"),
      parameterWithName("swLng").description("현재 지도 화면의 남서쪽 경도"),
      parameterWithName("neLat").description("현재 지도 화면의 북동쪽 위도. swLat보다 큰 값"),
      parameterWithName("neLng").description("현재 지도 화면의 북동쪽 경도. swLng보다 큰 값"),
      parameterWithName("minBudget")
          .optional()
          .description("월세 최소값(KRW). 목록 필터와 같은 값으로 마커도 같이 갱신할 때 사용"),
      parameterWithName("maxBudget")
          .optional()
          .description("월세 최대값(KRW). 조건에 맞는 방 타입이 있는 매물의 마커만 반환"),
      parameterWithName("minDeposit").optional().description("보증금 최소값(KRW)"),
      parameterWithName("maxDeposit").optional().description("보증금 최대값(KRW)"),
      parameterWithName("type")
          .optional()
          .description("매물 유형 필터 칩. 예: GOSHIWON, CO_LIVING, SHARE_HOUSE, OTHER"),
      parameterWithName("conditions")
          .optional()
          .description("옵션 필터 칩 코드. 목록 API와 같은 필터를 보내면 지도 마커와 바텀시트 목록을 같은 조건으로 맞출 수 있음")
    };
  }

  /** 키워드 검색 API의 query parameter 문서 정의다. */
  private static ParameterDescriptor[] searchQueryParameters() {
    return new ParameterDescriptor[] {
      parameterWithName("keyword")
          .description(
              "검색창 입력값(1~50자). 학교명·지역명·지하철역명 또는 별칭 일부를 보낼 수 있음. 예: 연세, 연세대, 서울대, 신촌, 홍대입구역"),
      parameterWithName("sort")
          .optional()
          .description(
              "검색 결과 정렬 방식. 기본 DISTANCE는 검색된 장소에서 가까운 순, PRICE_ASC는 조건에 맞는 방 타입 중 가장 낮은 월세순, RECOMMENDED는 추천순"),
      parameterWithName("page").optional().description("0부터 시작하는 페이지 번호"),
      parameterWithName("size").optional().description("한 번에 가져올 매물 수. 기본 20, 최대 100")
    };
  }

  /** 찜 등록/해제 API의 path parameter 문서 정의다. */
  private static ParameterDescriptor[] favoritePathParameters() {
    return new ParameterDescriptor[] {
      parameterWithName("listingId")
          .description("하트를 누른 매물의 listingId. 목록/상세 응답의 listingId를 그대로 path에 넣으면 됨")
    };
  }

  /** 내 찜 목록 API의 query parameter 문서 정의다. */
  private static ParameterDescriptor[] favoritesQueryParameters() {
    return new ParameterDescriptor[] {
      parameterWithName("page").optional().description("0부터 시작하는 페이지 번호"),
      parameterWithName("size").optional().description("한 번에 가져올 찜 매물 수. 기본 20, 최대 100")
    };
  }

  /** 지도 마커 API 응답 필드 문서 정의다. */
  private static List<FieldDescriptor> mapResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"),
        field("data.markers[].listingId", JsonFieldType.STRING, "마커 선택 시 목록 항목/상세 화면과 연결할 매물 ID"),
        field("data.markers[].lat", JsonFieldType.NUMBER, "지도 SDK에 넘길 마커 위도"),
        field("data.markers[].lng", JsonFieldType.NUMBER, "지도 SDK에 넘길 마커 경도"),
        field("data.total", JsonFieldType.NUMBER, "현재 지도 영역과 필터 조건에 맞는 마커 수. 클러스터/빈 상태 판단에 사용"),
        errorNull());
  }

  /** 찜 등록/해제 API 응답 필드 문서 정의다. */
  private static List<FieldDescriptor> favoriteToggleResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"),
        field(
            "data.favorited", JsonFieldType.BOOLEAN, "요청 처리 후 하트 상태. true면 채운 하트, false면 빈 하트로 갱신"),
        field("data.favoriteCount", JsonFieldType.NUMBER, "요청 처리 후 화면에 표시할 최신 찜 수"),
        errorNull());
  }

  /** 내 찜 목록 API 응답 필드 문서 정의다. */
  private static List<FieldDescriptor> favoritesResponseFields() {
    List<FieldDescriptor> fields = new ArrayList<>();
    fields.add(field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"));
    fields.addAll(listingDocumentFields("data.content[]", null));
    fields.add(field("data.content[].favoritedAt", JsonFieldType.STRING, "찜 목록 정렬/보조 문구에 쓸 찜한 시각"));
    fields.addAll(pageFields("공개 상태라 실제 응답 가능한 내 찜 매물 수"));
    fields.add(errorNull());
    return fields;
  }

  /** 최근 본 매물 목록 API 응답 필드 문서 정의다. */
  private static List<FieldDescriptor> recentListingsResponseFields() {
    List<FieldDescriptor> fields = new ArrayList<>();
    fields.add(field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"));
    fields.addAll(listingDocumentFields("data.content[]", null));
    fields.add(
        field("data.content[].viewedAt", JsonFieldType.STRING, "최근 본 목록 정렬/보조 문구에 쓸 마지막 상세 조회 시각"));
    fields.add(errorNull());
    return fields;
  }

  /** 목록 API 응답 필드 문서 정의다. */
  private static List<FieldDescriptor> listResponseFields() {
    List<FieldDescriptor> fields = new ArrayList<>();
    fields.add(field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"));
    fields.addAll(
        listingDocumentFields("data.content[]", "지도 중심에서 매물까지의 직선거리(미터). 카드 거리 라벨에 사용하고 없으면 숨김"));
    fields.addAll(pageFields("필터와 지도 범위에 맞는 전체 매물 수"));
    fields.add(errorNull());
    return fields;
  }

  /** 키워드 검색 성공 응답 필드 문서 정의다. */
  private static List<FieldDescriptor> searchResponseFields() {
    List<FieldDescriptor> fields = new ArrayList<>();
    fields.add(field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"));
    fields.add(
        field(
            "data.matchedPlace.type",
            JsonFieldType.STRING,
            "검색어로 매칭된 장소 종류. UNIVERSITY, SUBWAY_STATION, REGION 중 하나"));
    fields.add(field("data.matchedPlace.name", JsonFieldType.STRING, "프론트에 표시할 공식 장소명"));
    fields.add(field("data.matchedPlace.lat", JsonFieldType.NUMBER, "지도 중심 이동에 사용할 장소 위도(WGS84)"));
    fields.add(field("data.matchedPlace.lng", JsonFieldType.NUMBER, "지도 중심 이동에 사용할 장소 경도(WGS84)"));
    fields.addAll(
        listingDocumentFields("data.content[]", "검색된 장소에서 매물까지의 직선거리(미터). 검색 결과 카드 거리 라벨에 사용"));
    fields.addAll(pageFields("검색 장소 3km 이내에 있는 전체 매물 수"));
    fields.add(errorNull());
    return fields;
  }

  private static List<FieldDescriptor> listingDocumentFields(
      String prefix, String distanceDescription) {
    List<FieldDescriptor> fields = new ArrayList<>();
    fields.add(
        field(prefix + ".listingId", JsonFieldType.STRING, "상세 이동, 하트 토글, 예약 진입에 사용할 매물 ID"));
    fields.add(field(prefix + ".title", JsonFieldType.STRING, "카드와 상세 상단에 표시할 매물 이름"));
    fields.add(
        field(
            prefix + ".type.code",
            JsonFieldType.STRING,
            "매물 유형의 서버 코드. 필터 요청과 내부 비교에는 이 값을 사용. 예: GOSHIWON"));
    fields.add(
        field(
            prefix + ".type.label",
            JsonFieldType.STRING,
            "현재 사용자 언어의 매물 유형 표시명. 화면 배지에는 이 값을 그대로 표시"));
    fields.add(
        field(
            prefix + ".status",
            JsonFieldType.STRING,
            "공개 상태. 일반 화면에서는 PUBLISHED만 내려오므로 별도 필터링 없이 표시 가능"));
    fields.add(
        field(
            prefix + ".rentalType.code",
            JsonFieldType.STRING,
            "임대 방식 서버 코드. 요청/비교용이며 예시는 MONTHLY_RENT"));
    fields.add(
        field(
            prefix + ".rentalType.label",
            JsonFieldType.STRING,
            "가격 영역에 표시할 현재 언어의 임대 방식. 예: Monthly Rent"));
    fields.add(
        field(prefix + ".refundPolicy.code", JsonFieldType.STRING, "상세 화면 환불 정책 아이콘/분기용 코드"));
    fields.add(
        field(
            prefix + ".refundPolicy.description", JsonFieldType.STRING, "상세 화면에 그대로 보여줄 환불 정책 설명"));
    fields.add(
        field(
            prefix + ".contract.minStayMonths",
            JsonFieldType.NUMBER,
            "계약기간 라벨의 최소 개월 수. 예: 1개월부터"));
    fields.add(
        field(
            prefix + ".contract.maxStayMonths",
            JsonFieldType.NUMBER,
            "계약기간 라벨의 최대 개월 수. 예: 최대 12개월"));
    fields.add(
        field(
            prefix + ".genderPolicy.code", JsonFieldType.STRING, "성별 제한의 서버 코드. 필터 요청과 내부 비교에 사용"));
    fields.add(
        field(prefix + ".genderPolicy.label", JsonFieldType.STRING, "성별 제한 배지에 표시할 현재 언어의 문구"));
    fields.add(field(prefix + ".location.lat", JsonFieldType.NUMBER, "상세 지도 또는 선택 마커 중심에 사용할 위도"));
    fields.add(field(prefix + ".location.lng", JsonFieldType.NUMBER, "상세 지도 또는 선택 마커 중심에 사용할 경도"));
    fields.add(field(prefix + ".address.city", JsonFieldType.STRING, "지역 필터/주소 보조 표시용 도시 코드"));
    fields.add(field(prefix + ".address.district", JsonFieldType.STRING, "지역 필터/주소 보조 표시용 구·군 코드"));
    fields.add(
        field(prefix + ".address.fullAddress", JsonFieldType.STRING, "카드와 상세 주소 영역에 표시할 주소"));
    fields.add(field(prefix + ".address.detail", JsonFieldType.NULL, "상세주소. null이면 상세주소 줄을 숨김"));
    fields.add(
        field(
            prefix + ".nearestTransit.type.code",
            JsonFieldType.STRING,
            "교통 배지 아이콘 분기용 서버 코드. 예: SUBWAY, BUS"));
    fields.add(
        field(
            prefix + ".nearestTransit.type.label",
            JsonFieldType.STRING,
            "교통수단 이름으로 표시할 현재 언어의 문구. 예: Subway"));
    fields.add(field(prefix + ".nearestTransit.name", JsonFieldType.STRING, "교통 배지에 표시할 역/정류장 이름"));
    fields.add(
        field(
            prefix + ".nearestTransit.walkMinutes",
            JsonFieldType.NUMBER,
            "'도보 N분' 문구에 사용할 분 단위 값"));
    fields.add(
        field(
            prefix + ".nearestTransit.nearbyPlacesDescription",
            JsonFieldType.STRING,
            "주변 편의시설 안내 문구. 없으면 주변 안내 문단을 숨김"));
    fields.add(
        field(
            prefix + ".nearbyUniversityCodes",
            JsonFieldType.ARRAY,
            "학교 주변 배지나 학교 필터 매칭에 사용할 학교 코드 목록"));
    fields.add(field(prefix + ".building.type.code", JsonFieldType.STRING, "건물 유형 서버 코드. 요청/비교용"));
    fields.add(
        field(prefix + ".building.type.label", JsonFieldType.STRING, "건물 정보 섹션에 표시할 현재 언어의 건물 유형"));
    fields.add(field(prefix + ".building.usedFloorMin", JsonFieldType.NUMBER, "매물이 사용하는 시작 층"));
    fields.add(field(prefix + ".building.usedFloorMax", JsonFieldType.NUMBER, "매물이 사용하는 마지막 층"));
    fields.add(field(prefix + ".building.totalFloors", JsonFieldType.NUMBER, "건물 전체 층수"));
    fields.add(
        field(prefix + ".building.parkingAvailable", JsonFieldType.BOOLEAN, "주차 가능 아이콘/텍스트 표시 여부"));
    fields.add(
        field(
            prefix + ".building.elevatorAvailable", JsonFieldType.BOOLEAN, "엘리베이터 아이콘/텍스트 표시 여부"));
    fields.add(
        field(
            prefix + ".propertyPolicies.arcRequired",
            JsonFieldType.BOOLEAN,
            "ARC 필요 여부. false면 No ARC 가능 배지로 표시 가능"));
    fields.add(
        field(
            prefix + ".propertyPolicies.residentRegistrationAvailable",
            JsonFieldType.BOOLEAN,
            "전입신고 가능 배지 표시 여부"));
    fields.add(
        field(
            prefix + ".propertyPolicies.studySuitable",
            JsonFieldType.BOOLEAN,
            "학업/조용한 거주 적합 배지 표시 여부"));
    fields.add(
        field(prefix + ".propertyPolicies.mealsProvided", JsonFieldType.BOOLEAN, "식사 제공 배지 표시 여부"));
    fields.add(
        field(
            prefix + ".propertyPolicies.englishAvailable",
            JsonFieldType.BOOLEAN,
            "영어 소통 가능 배지 표시 여부"));
    fields.add(
        field(
            prefix + ".facilities.heatingSystem",
            JsonFieldType.ARRAY,
            "난방 방식 code/label 목록. building이 아니라 여기서 읽고 label을 표시"));
    addCodeLabelArrayFields(fields, prefix + ".facilities.heatingSystem", "난방 방식");
    fields.add(
        field(prefix + ".facilities.kitchen", JsonFieldType.ARRAY, "주방/조리 시설 code/label 목록"));
    addCodeLabelArrayFields(fields, prefix + ".facilities.kitchen", "주방/조리 시설");
    fields.add(field(prefix + ".facilities.laundry", JsonFieldType.ARRAY, "세탁 시설 code/label 목록"));
    addCodeLabelArrayFields(fields, prefix + ".facilities.laundry", "세탁 시설");
    fields.add(
        field(
            prefix + ".facilities.livingAmenities", JsonFieldType.ARRAY, "생활 편의시설 code/label 목록"));
    addCodeLabelArrayFields(fields, prefix + ".facilities.livingAmenities", "생활 편의시설");
    fields.add(
        field(prefix + ".facilities.securityFeatures", JsonFieldType.ARRAY, "보안 시설 code/label 목록"));
    addCodeLabelArrayFields(fields, prefix + ".facilities.securityFeatures", "보안 시설");
    fields.add(
        field(prefix + ".facilities.commonSpaces[].type.code", JsonFieldType.STRING, "공용공간 서버 코드"));
    fields.add(
        field(
            prefix + ".facilities.commonSpaces[].type.label",
            JsonFieldType.STRING,
            "공용공간 칩에 표시할 현재 언어 문구"));
    fields.add(
        fieldWithPath(prefix + ".facilities.commonSpaces[].count")
            .type(JsonFieldType.VARIES)
            .optional()
            .description("공용공간 수량. null이면 수량 없이 유형만 표시"));
    fields.add(
        field(prefix + ".facilities.providedSupplies", JsonFieldType.ARRAY, "제공 물품 code/label 목록"));
    addCodeLabelArrayFields(fields, prefix + ".facilities.providedSupplies", "제공 물품");
    fields.add(
        field(
            prefix + ".conditions",
            JsonFieldType.ARRAY,
            "매물 단위 조건 배지 목록. ACTIVE roomOffers[].filterTags의 합집합이며, propertyPolicies.arcRequired=false이면 NO_ARC가 추가됨. "
                + "카드 조건 배지나 상세 Property Details의 features에 바로 사용하고, 방 타입별 조건은 roomOffers[].filterTags를 사용"));
    addCodeLabelArrayFields(fields, prefix + ".conditions", "매물 조건");
    fields.add(
        field(
            prefix + ".roomOffers[].roomOfferId",
            JsonFieldType.STRING,
            "방 타입 선택, 예약/문의 진입에 사용할 방 타입 ID"));
    fields.add(
        field(prefix + ".roomOffers[].name", JsonFieldType.STRING, "Room Types 영역에 표시할 방 타입 이름"));
    fields.add(
        field(
            prefix + ".roomOffers[].status",
            JsonFieldType.STRING,
            "방 타입 상태. 일반 화면에는 ACTIVE만 내려오므로 그대로 표시 가능"));
    fields.add(
        field(
            prefix + ".roomOffers[].pricing.monthlyRent",
            JsonFieldType.NUMBER,
            "월세 표시값(KRW). 카드 가격 범위 계산에도 사용"));
    fields.add(
        field(prefix + ".roomOffers[].pricing.deposit", JsonFieldType.NUMBER, "보증금 표시값(KRW)"));
    fields.add(
        field(
            prefix + ".roomOffers[].pricing.maintenanceFee",
            JsonFieldType.NUMBER,
            "관리비 표시값(KRW). 0이면 관리비 없음 배지로 표시 가능"));
    fields.add(
        field(prefix + ".roomOffers[].pricing.currency", JsonFieldType.STRING, "금액 통화. 현재 KRW"));
    fields.add(
        field(
            prefix + ".roomOffers[].inventory.totalCount",
            JsonFieldType.NUMBER,
            "같은 가격/조건의 전체 방 수. 재고 상세 표시가 필요할 때 사용"));
    fields.add(
        field(
            prefix + ".roomOffers[].inventory.availableCount",
            JsonFieldType.NUMBER,
            "현재 계약 가능한 방 수. 0이면 마감/대기 상태로 표시 가능"));
    fields.add(
        field(
            prefix + ".roomOffers[].inventory.nextAvailableFrom",
            JsonFieldType.NULL,
            "다음 입주 가능일. null이면 별도 날짜 문구를 숨김"));
    fields.add(
        field(
            prefix + ".roomOffers[].filterTags",
            JsonFieldType.ARRAY,
            "해당 방 타입에만 붙는 조건 배지 목록. 매물 전체 조건 배지는 상위 conditions를 사용"));
    addCodeLabelArrayFields(fields, prefix + ".roomOffers[].filterTags", "방 조건");
    fields.add(
        field(
            prefix + ".roomOffers[].roomImageUrls",
            JsonFieldType.ARRAY,
            "방 타입별 이미지 목록. 비어 있으면 공용 imageUrls 사용 가능"));
    fields.add(
        field(
            prefix + ".descriptions.description",
            JsonFieldType.STRING,
            "현재 사용자 언어로 서버가 선택한 상세 설명. 프론트는 별도 ko/en 분기 없이 그대로 표시"));
    fields.add(
        field(prefix + ".descriptions.extraNotes", JsonFieldType.STRING, "상세 화면의 추가 안내/주의사항"));
    fields.add(
        field(
            prefix + ".imageUrls",
            JsonFieldType.ARRAY,
            "카드 썸네일과 상세 갤러리에 사용할 공용 이미지 목록. 카드 대표 이미지는 첫 번째 값 사용"));
    if (distanceDescription != null) {
      fields.add(field(prefix + ".distanceMeters", JsonFieldType.NUMBER, distanceDescription));
    }
    fields.add(
        field(prefix + ".favorited", JsonFieldType.BOOLEAN, "현재 사용자의 하트 상태. true면 채운 하트로 표시"));
    fields.add(field(prefix + ".favoriteCount", JsonFieldType.NUMBER, "카드/상세에 표시할 찜 수"));
    fields.add(field(prefix + ".createdAt", JsonFieldType.STRING, "매물 생성 시각. 일반 UI에서 필요 없으면 숨김"));
    fields.add(field(prefix + ".updatedAt", JsonFieldType.STRING, "매물 수정 시각. 최신 정보 표시가 필요할 때 사용"));
    return fields;
  }

  private static List<FieldDescriptor> pageFields(String totalElementsDescription) {
    return List.of(
        field("data.page.number", JsonFieldType.NUMBER, "현재 페이지 번호"),
        field("data.page.size", JsonFieldType.NUMBER, "페이지 크기"),
        field("data.page.totalElements", JsonFieldType.NUMBER, totalElementsDescription),
        field("data.page.totalPages", JsonFieldType.NUMBER, "전체 페이지 수"),
        field("data.page.hasNext", JsonFieldType.BOOLEAN, "다음 페이지 존재 여부"));
  }

  /** code/label 객체 배열의 공통 하위 필드 설명을 추가한다. */
  private static void addCodeLabelArrayFields(
      List<FieldDescriptor> fields, String arrayPath, String subject) {
    fields.add(
        field(
            arrayPath + "[].code",
            JsonFieldType.STRING,
            subject + " 서버 코드. 필터 요청과 내부 비교에는 이 값을 사용"));
    fields.add(
        field(
            arrayPath + "[].label",
            JsonFieldType.STRING,
            subject + "의 현재 사용자 언어 표시명. 화면에는 이 값을 사용"));
  }

  /** POI 매칭이 없는 키워드 검색 응답 필드 문서 정의다. */
  private static List<FieldDescriptor> searchEmptyPlaceResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"),
        field(
            "data.matchedPlace",
            JsonFieldType.NULL,
            "검색어와 일치하는 장소가 없다는 뜻. '검색된 장소가 없어요' 상태를 표시하면 됨"),
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
    List<FieldDescriptor> fields = new ArrayList<>();
    fields.add(field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"));
    fields.addAll(listingDocumentFields("data", null));
    fields.add(errorNull());
    return fields;
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
