package com.kohere.listing.infrastructure;

import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.document;
import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.resourceDetails;
import static com.epages.restdocs.apispec.ResourceDocumentation.resource;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get;
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
      "현재 지도 화면의 남서/북동 좌표를 기준으로 공개 매물 리스트를 조회한다. "
          + "지도 범위 좌표가 전달되면 서버가 전체 범위를 20% 확장해 조회하며, "
          + "필터가 없으면 해당 범위의 공개 매물을, 필터가 있으면 같은 범위 안에서 조건을 만족하는 매물만 반환한다. "
          + "월세·보증금·매물 종류·매물 옵션·ARC·전입신고 필터를 지원한다. "
          + "이 API는 지도 바텀시트 리스트 카드용이며, 지도 핀/클러스터 데이터는 별도 지도 API에서 제공한다.";
  private static final String LISTING_DETAIL_SUMMARY = "매물 상세 조회";
  private static final String LISTING_DETAIL_DESCRIPTION =
      "매물 카드나 지도 핀에서 선택한 단일 매물의 상세 정보를 조회한다. "
          + "상세 화면의 이미지, 각 방 정보, 가격 정보, 매물 정보, 건물 정보, 공용시설, 위치 및 주변 정보 섹션을 구성하는 데이터를 반환한다. "
          + "고시원 매물의 propertyInfo.featureSummary는 활성 방 상품들이 가진 조건 태그의 합집합이다. "
          + "상세 화면의 작은 지도는 응답의 locationInfo.location 좌표를 사용해 프론트에서 렌더링한다. "
          + "공개 상태가 아닌 매물이나 존재하지 않는 매물은 LISTING_NOT_FOUND를 반환한다.";

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
    new ListingSeedRunner(listingRepository).run(null);
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
                .param("arcRequired", "false")
                .param("residentRegistration", "true")
                .param("sort", "PRICE_ASC")
                .param("centerLat", "37.459471")
                .param("centerLng", "126.951422")
                .param("page", "0")
                .param("size", "20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[0].listingId").value(LISTING_ID))
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
            get("/api/v1/listings/{listingId}", LISTING_ID)
                .header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.listingId").value(LISTING_ID))
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
      parameterWithName("minBudget").optional().description("월세 하한(KRW). maxBudget보다 클 수 없음"),
      parameterWithName("maxBudget").optional().description("월세 상한(KRW). minBudget보다 작을 수 없음"),
      parameterWithName("minDeposit").optional().description("보증금 하한(KRW). maxDeposit보다 클 수 없음"),
      parameterWithName("maxDeposit").optional().description("보증금 상한(KRW). minDeposit보다 작을 수 없음"),
      parameterWithName("type")
          .optional()
          .description("매물 종류 필터. 예: GOSIWON, CO_LIVING, SHARE_HOUSE"),
      parameterWithName("conditions")
          .optional()
          .description("매물 옵션 태그. 여러 값을 보내면 모두 만족하는 방 상품만 조회"),
      parameterWithName("arcRequired").optional().description("false면 ARC 없이 계약 가능한 매물만 조회"),
      parameterWithName("residentRegistration")
          .optional()
          .description("true면 전입신고 가능 태그가 있는 방 상품만 조회. conditions=RESIDENT_REGISTRATION과 같은 의미"),
      parameterWithName("sort")
          .optional()
          .description("정렬 프리셋. RECOMMENDED, PRICE_ASC, DISTANCE 중 하나. 기본값은 RECOMMENDED"),
      parameterWithName("centerLat")
          .optional()
          .description("거리 계산 또는 DISTANCE 정렬 기준 위도. sort=DISTANCE이면 centerLat/centerLng 모두 필수"),
      parameterWithName("centerLng")
          .optional()
          .description("거리 계산 또는 DISTANCE 정렬 기준 경도. sort=DISTANCE이면 centerLat/centerLng 모두 필수"),
      parameterWithName("page").optional().description("0-base 페이지 번호(기본 0)"),
      parameterWithName("size").optional().description("페이지 크기(기본 20, 최대 100)")
    };
  }

  /** 목록 API 응답 필드 문서 정의다. */
  private static List<FieldDescriptor> listResponseFields() {
    return List.of(
        field("success", JsonFieldType.BOOLEAN, "성공 여부 — 항상 true"),
        field("data.content[].listingId", JsonFieldType.STRING, "매물 식별자(ObjectId hex 문자열)"),
        field("data.content[].title", JsonFieldType.STRING, "매물 제목"),
        field("data.content[].type", JsonFieldType.STRING, "매물 유형"),
        field(
            "data.content[].monthlyRent",
            JsonFieldType.NUMBER,
            "필터 조건을 만족하는 대표 방 상품의 월세(KRW). 대표 방 상품은 조건에 맞는 활성 방 중 월세가 가장 낮은 방"),
        field("data.content[].deposit", JsonFieldType.NUMBER, "대표 방 상품의 보증금(KRW)"),
        field("data.content[].maintenanceFee", JsonFieldType.NUMBER, "대표 방 상품의 관리비(KRW)"),
        field("data.content[].thumbnailUrl", JsonFieldType.NULL, "썸네일 URL(없으면 null)"),
        field("data.content[].lat", JsonFieldType.NUMBER, "위도(WGS84)"),
        field("data.content[].lng", JsonFieldType.NUMBER, "경도(WGS84)"),
        field("data.content[].address", JsonFieldType.STRING, "카드에 표시할 주소"),
        field("data.content[].conditions", JsonFieldType.ARRAY, "대표 방 상품의 옵션 태그 목록"),
        field(
            "data.content[].distanceMeters",
            JsonFieldType.NUMBER,
            "centerLat/centerLng를 보냈을 때 계산되는 직선 거리(미터). 기준 좌표가 없으면 null"),
        field(
            "data.content[].favorited",
            JsonFieldType.BOOLEAN,
            "현재 사용자 찜 여부. 현재는 사용자별 찜 연동 전이라 false로 반환하며, 찜 API 연동 후 사용자별 상태로 변경 예정"),
        field("data.content[].favoriteCount", JsonFieldType.NUMBER, "찜 수"),
        field("data.page.number", JsonFieldType.NUMBER, "현재 페이지 번호"),
        field("data.page.size", JsonFieldType.NUMBER, "페이지 크기"),
        field("data.page.totalElements", JsonFieldType.NUMBER, "전체 건수"),
        field("data.page.totalPages", JsonFieldType.NUMBER, "전체 페이지 수"),
        field("data.page.hasNext", JsonFieldType.BOOLEAN, "다음 페이지 존재 여부"),
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
        field("data.roomOffers[].status", JsonFieldType.STRING, "방 상품 상태. ACTIVE인 방이 실제 노출/계약 대상"),
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
            "data.interaction.favorited",
            JsonFieldType.BOOLEAN,
            "현재 사용자 찜 여부. 현재는 사용자별 찜 연동 전이라 false로 반환하며, 찜 API 연동 후 사용자별 상태로 변경 예정"),
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
