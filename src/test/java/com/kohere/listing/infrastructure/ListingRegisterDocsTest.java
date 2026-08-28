package com.kohere.listing.infrastructure;

import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.document;
import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.resourceDetails;
import static com.kohere.docs.ApiDocsErrors.errorSnippet;
import static com.kohere.docs.DocsTokens.bearer;
import static com.kohere.docs.DocsTokens.expiredAccessToken;
import static com.kohere.docs.ListingDocsFields.LISTING_REGISTER_400;
import static com.kohere.docs.ListingDocsFields.LISTING_REGISTER_401;
import static com.kohere.docs.ListingDocsFields.LISTING_REGISTER_403;
import static com.kohere.docs.ListingDocsFields.LISTING_REGISTER_DESCRIPTION;
import static com.kohere.docs.ListingDocsFields.LISTING_REGISTER_SUMMARY;
import static com.kohere.docs.ListingDocsFields.registerRequestFields;
import static com.kohere.docs.ListingDocsFields.registerResponseFields;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.BDDMockito.given;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.post;
import static org.springframework.restdocs.payload.PayloadDocumentation.requestFields;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kohere.TestcontainersConfiguration;
import com.kohere.common.security.JwtProperties;
import com.kohere.common.security.JwtTokenService;
import com.kohere.docs.ApiDocsTags;
import com.kohere.user.api.UserAccountService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 매물 등록({@code POST /api/v2/listings})의 REST Docs 스니펫 생성 테스트다. 매물 도메인의 첫 {@code /api/v2} 엔드포인트이고 조회
 * 계열과 요청·응답 계약이 겹치지 않아 {@link ListingDocsTest}와 파일을 나눴다(진단의 {@code DiagnosisDocsTest}·{@code
 * DiagnosisV2DocsTest}와 같은 갈래).
 *
 * <p>문구·필드 기술자는 {@code ListingDocsFields}에 한 벌만 두고 여기서는 흐름만 만든다 — 성공·에러 스니펫이 같은 오퍼레이션으로 병합되므로
 * summary·description은 상수 하나를, 같은 status의 에러 스니펫은 같은 코드 배열을 써야 한다(ADR-0017).
 *
 * <p>등록 매물은 {@code PENDING}이라 다른 문서 테스트의 조회 개수 단정을 흔들 수 있다. 이 클래스는 자기 MongoDB 컨테이너를 쓰고
 * {@code @BeforeEach}마다 매물 컬렉션을 비우므로 시드는 코드 카탈로그 하나뿐이다.
 *
 * <p>cross-module 협력({@code user :: api}의 {@code userType}·표시 언어)은 {@link MockitoBean}으로 대체하고
 * access 토큰은 {@link JwtTokenService}로 직접 발급한다(test-strategy §4).
 */
@SpringBootTest
@ExtendWith(RestDocumentationExtension.class)
@ActiveProfiles("test")
@Testcontainers
@Import(TestcontainersConfiguration.class)
class ListingRegisterDocsTest {

  /** 등록 권한이 있는 임대인 계정이다. 표시 언어가 한국어라 응답 label도 한국어로 내려간다. */
  private static final long LANDLORD_ID = 42L;

  /** 정식 회원이지만 임대인이 아니라 서비스가 403으로 거르는 계정이다. */
  private static final long TENANT_ID = 1L;

  private static final String LISTINGS_COLLECTION = "listings";
  private static final String LISTING_CATALOG_COLLECTION = "listingCatalog";
  private static final String UNIVERSITIES_COLLECTION = "universities";

  /** 코드표 불일치를 재현하려고 지우는 카탈로그 항목의 문서 id다(`category:code`). */
  private static final String NO_MAINT_FEE_CATALOG_ID = "CONDITION_TAG:NO_MAINT_FEE";

  private static final String MALFORMED_BODY = "{ \"oops\" }";

  /** 사진 part의 형식·내용. 서버는 바이트를 해석하지 않고 형식과 크기만 본다. */
  private static final String IMAGE_CONTENT_TYPE = "image/jpeg";

  private static final byte[] IMAGE_BYTES = "fake-jpeg-bytes".getBytes(StandardCharsets.UTF_8);

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

  /**
   * 스펙 예시와 같은 정상 등록 본문이다. 에러 스니펫은 {@link #bodyReplacing(String, String)}으로 여기서 한 곳만 바꿔 쓴다.
   *
   * <p>다국어 문구는 한국어 한 값만 담는다 — 서버가 저장할 때 영어 자리에 같은 값을 복사한다.
   */
  private static final String REGISTER_BODY =
      """
      {
        "title": "신촌 도보 5분 1인실 고시원",
        "type": "GOSHIWON",
        "contact": {
          "managerName": "김운영",
          "phone": "+82) 10-1234-5678"
        },
        "businessRegistrationNumber": "1234567890",
        "blogUrl": "https://blog.naver.com/kohere-goshiwon",
        "address": {
          "fullAddress": "서울특별시 서대문구 신촌로 12",
          "detail": "3층 305호",
          "lat": 37.5559918,
          "lng": 126.9368647
        },
        "building": {
          "type": "VILLA",
          "totalFloors": 4,
          "usedFloorRange": "1~2",
          "parkingAvailable": true,
          "elevatorAvailable": true
        },
        "genderPolicy": "FEMALE_ONLY",
        "languagesSupported": ["ENGLISH", "CHINESE"],
        "ageRange": "20~35",
        "arcRequired": "NOT_REQUIRED",
        "facilities": {
          "heatingSystem": ["CENTRAL"],
          "kitchen": ["SHARED_REFRIGERATOR", "MICROWAVE"],
          "laundry": ["WASHER", "DRYING_RACK"],
          "livingAmenities": ["WIFI", "TV"],
          "securityFeatures": ["CCTV", "ENTRANCE_DOOR_LOCK"],
          "commonSpaces": ["SHARED_KITCHEN", "SHARED_TOILET"],
          "providedSupplies": ["BEDDING", "TISSUE"]
        },
        "nearbyFacilities": ["CONVENIENCE_STORE", "HOSPITAL_PHARMACY"],
        "nearestTransit": {
          "type": "SUBWAY",
          "name": "신촌역",
          "walkMinutes": 5
        },
        "description": "지하철역에서 도보 5분 거리의 관리가 잘 된 고시원입니다.",
        "extraNotes": "객실 내 취사 금지. 오후 11시 이후 정숙.",
        "refundPolicy": "입주 7일 전까지 취소하면 전액 환불합니다.",
        "imageKeys": ["uploads/42/3f9a1c2e-1d2b-4c3a-9f10-2b7c5d8e4a11.jpg"],
        "roomOffers": [
          {
            "name": "스탠다드 1인실",
            "contract": { "minStayMonths": 1, "maxStayMonths": 12 },
            "pricing": {
              "monthlyRent": 380000,
              "deposit": 200000,
              "maintenanceFee": 20000
            },
            "filterTags": ["ENGLISH_OK", "ADDRESS_REGISTRATION"],
            "roomImageKeys": [
              "uploads/42/7b2e8841-2a3b-4c5d-8e9f-0a1b2c3d4e55.jpg",
              "uploads/42/c14d05a6-6b7c-4d8e-9f01-2a3b4c5d6e66.jpg"
            ]
          }
        ],
        "preferredNationalities": ["JAPAN", "CHINA"],
        "contractDifficulties": ["LANGUAGE", "PAYMENT"],
        "serviceFeedback": "외국인 세입자용 계약서 번역 템플릿이 있으면 좋겠습니다."
      }
      """;

  @Container @ServiceConnection static MongoDBContainer mongo = new MongoDBContainer("mongo:7.0");

  @Autowired private WebApplicationContext context;
  @Autowired private MongoTemplate mongoTemplate;
  @Autowired private JwtTokenService jwtTokenService;
  @Autowired private JwtProperties jwtProperties;

  @MockitoBean private UserAccountService userAccountService;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private MockMvc mockMvc;

  /** REST Docs용 MockMvc를 만들고 매물 컬렉션을 비운 뒤 코드 카탈로그(번역 사전)와 대학 좌표 원장을 시드한다. */
  @BeforeEach
  void setUp(RestDocumentationContextProvider restDocumentation) {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .apply(springSecurity())
            .apply(documentationConfiguration(restDocumentation))
            .build();
    mongoTemplate.getCollection(LISTINGS_COLLECTION).deleteMany(new Document());
    mongoTemplate.getCollection(LISTING_CATALOG_COLLECTION).deleteMany(new Document());
    mongoTemplate.getCollection(UNIVERSITIES_COLLECTION).deleteMany(new Document());
    ListingTestSeeds.seedCatalog(mongoTemplate, LISTING_CATALOG_COLLECTION);
    // 등록이 좌표로 인근 대학을 파생하므로 운영과 같은 정본 원장을 심는다(ADR-0045).
    ListingTestSeeds.seedUniversities(mongoTemplate, UNIVERSITIES_COLLECTION);
    given(userAccountService.getUserType(LANDLORD_ID)).willReturn("LANDLORD");
    given(userAccountService.getLanguage(LANDLORD_ID)).willReturn("ko");
    given(userAccountService.getUserType(TENANT_ID)).willReturn("TENANT");
  }

  /** 임대인 등록 성공 — 서버가 채우는 값과 폼 1칸에서 나뉜 값, 주소에서 뽑은 행정구역을 함께 단정한다. */
  @Test
  void 문서스니펫생성_매물등록_PENDING과검색좌표로저장() throws Exception {
    mockMvc
        .perform(register(landlordToken(), REGISTER_BODY))
        .andExpect(status().isCreated())
        // 요청에 없던 서버 발급 값들.
        .andExpect(jsonPath("$.data.listingId").isString())
        .andExpect(jsonPath("$.data.status").value("PENDING"))
        .andExpect(jsonPath("$.data.rentalType.code").value("MONTHLY_RENT"))
        .andExpect(jsonPath("$.data.favorited").value(false))
        .andExpect(jsonPath("$.data.favoriteCount").value(0))
        .andExpect(jsonPath("$.data.createdAt").isString())
        .andExpect(jsonPath("$.data.roomOffers[0].roomOfferId").isString())
        .andExpect(jsonPath("$.data.roomOffers[0].status").value("ACTIVE"))
        .andExpect(jsonPath("$.data.roomOffers[0].pricing.currency").value("KRW"))
        // 주소 검색이 준 좌표를 요청으로 되돌려 받아 저장한다(ADR-0042). address 안이 아니라 최상위
        // location으로 옮겨 가는 것도 함께 못 박는다 — 상세 조회와 같은 구조여야 한다.
        .andExpect(jsonPath("$.data.location.lat").value(37.5559918))
        .andExpect(jsonPath("$.data.location.lng").value(126.9368647))
        .andExpect(jsonPath("$.data.address.lat").doesNotExist())
        // 그 좌표에서 반경 2km 안의 대학을 서버가 파생한다(ADR-0045). 신촌로 12는 세 대학이 모두 도보권이라
        // 진단 그룹 HONGIK_YONSEI_EWHA와 그대로 맞물린다 — 요청에는 대학 칸이 없다.
        .andExpect(
            jsonPath("$.data.nearbyUniversityCodes")
                .value(containsInAnyOrder("YONSEI", "EWHA", "HONGIK")))
        // 한국어 한 값으로 보낸 문구가 임대인 언어(ko)로 그대로 돌아온다.
        .andExpect(jsonPath("$.data.title").value("신촌 도보 5분 1인실 고시원"))
        .andExpect(jsonPath("$.data.nearestTransit.name").value("신촌역"))
        .andExpect(jsonPath("$.data.type.code").value("GOSHIWON"))
        .andExpect(jsonPath("$.data.type.label").value("고시원"))
        // 도로명 주소에서 뽑은 행정구역은 요청에 없던 값이다. fullAddress는 보낸 그대로다.
        .andExpect(jsonPath("$.data.address.city.code").value("SEOUL"))
        .andExpect(jsonPath("$.data.address.district.code").value("SEODAEMUN_GU"))
        .andExpect(jsonPath("$.data.address.district.label").value("서대문구"))
        .andExpect(jsonPath("$.data.address.fullAddress").value("서울특별시 서대문구 신촌로 12"))
        .andExpect(jsonPath("$.data.address.detail").value("3층 305호"))
        // "1~2" 한 칸이 두 필드로 나뉜다.
        .andExpect(jsonPath("$.data.building.usedFloorMin").value(1))
        .andExpect(jsonPath("$.data.building.usedFloorMax").value(2))
        // 상위 conditions는 방 타입 filterTags의 합집합이라 요청에 없다.
        .andExpect(jsonPath("$.data.conditions[0].code").value("ENGLISH_OK"))
        .andExpect(jsonPath("$.data.conditions[1].code").value("ADDRESS_REGISTRATION"))
        .andExpect(jsonPath("$.data.conditions[1].label").value("전입신고 가능"))
        .andDo(
            document(
                "listing-register",
                resourceDetails()
                    .tag(ApiDocsTags.LISTINGS)
                    .summary(LISTING_REGISTER_SUMMARY)
                    .description(LISTING_REGISTER_DESCRIPTION),
                requestFields(registerRequestFields()),
                responseFields(registerResponseFields())));
  }

  /**
   * 카탈로그가 모르는 지역도 등록된다 — 행정구역은 {@code ETC}로 저장한다.
   *
   * <p>9개 구 목록은 데이터 무결성이 아니라 영업 범위 정책이라, 그 판단은 등록이 아니라 관리자 승인 심사가 한다. 예전에는 여기서 {@code 400
   * LISTING_INVALID_ADDRESS}가 났고 성북구·동작구처럼 대학이 있는 지역의 매물이 아예 들어오지 못했다.
   */
  @Test
  void 등록_카탈로그가_모르는_지역은_ETC로_저장한다() throws Exception {
    mockMvc
        .perform(
            register(
                landlordToken(), bodyReplacing("\"서울특별시 서대문구 신촌로 12\"", "\"서울특별시 성북구 안암로 145\"")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.address.city.code").value("SEOUL"))
        .andExpect(jsonPath("$.data.address.district.code").value("ETC"))
        .andExpect(jsonPath("$.data.address.district.label").value("기타"));
  }

  /** 등록 응답에는 사업자등록번호와 임대인 설문 3종이 없다. 저장은 하되 세입자에게 나가지 않는 값이라 응답 필드 기술자에도 없고, 없음을 여기서 못 박는다. */
  @Test
  void 등록응답_사업자등록번호와설문_응답에포함하지않는다() throws Exception {
    mockMvc
        .perform(register(landlordToken(), REGISTER_BODY))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.businessRegistrationNumber").doesNotExist())
        .andExpect(jsonPath("$.data.preferredNationalities").doesNotExist())
        .andExpect(jsonPath("$.data.contractDifficulties").doesNotExist())
        .andExpect(jsonPath("$.data.serviceFeedback").doesNotExist());
  }

  /**
   * 등록 직후 매물은 PENDING이라 공개 조회 어디에도 나오지 않는다 — description이 약속하는 계약이다.
   *
   * <p>반드시 {@code /api/v2}로 확인한다. {@code /api/v1} 조회는 저장소를 보지 않고 빈 결과·404를 주는 스텁이라(ADR-0040) 상태 필터가
   * 깨져도 그대로 통과해 버린다.
   */
  @Test
  void 등록직후매물_PENDING이라_목록에도상세에도나오지않는다() throws Exception {
    String listingId = registerListing();

    mockMvc
        .perform(get("/api/v2/listings").param("page", "0").param("size", "20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.page.totalElements").value(0))
        .andExpect(jsonPath("$.data.content").isEmpty());

    mockMvc
        .perform(get("/api/v2/listings/{listingId}", listingId))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("LISTING_NOT_FOUND"));
  }

  /** 스펙의 "발생 가능한 에러"를 실제로 트리거해 status·error.code와 실패 응답 스니펫을 함께 만든다. */
  @Test
  void 문서스니펫생성_스펙에적힌실패조건_status와errorcode가일치() throws Exception {
    // 보안 필터가 내는 401·403은 본문 없이도 도달하므로 요청 예시를 만들지 않는다(415를 피해 contentType만 남긴다).
    performError(
        emptyRegister().header(HttpHeaders.AUTHORIZATION, bearer(FORGED_TOKEN)),
        status().isUnauthorized(),
        "UNAUTHENTICATED",
        "listing-register-unauthenticated",
        LISTING_REGISTER_401);

    performError(
        emptyRegister()
            .header(HttpHeaders.AUTHORIZATION, bearer(expiredAccessToken(jwtProperties))),
        status().isUnauthorized(),
        "TOKEN_EXPIRED",
        "listing-register-token-expired",
        LISTING_REGISTER_401);

    performError(
        emptyRegister()
            .header(
                HttpHeaders.AUTHORIZATION, bearer(jwtTokenService.issueOnboardingToken(TENANT_ID))),
        status().isForbidden(),
        "AUTH_ONBOARDING_REQUIRED",
        "listing-register-onboarding-required",
        LISTING_REGISTER_403);

    // 임대인 여부는 서비스가 본다 — 유효한 본문이 있어야 컨트롤러에 도달하므로 본문을 그대로 싣는다.
    performError(
        register(tenantToken(), REGISTER_BODY),
        status().isForbidden(),
        "FORBIDDEN",
        "listing-register-forbidden",
        LISTING_REGISTER_403);

    // min~max 형식 위반. 어느 칸이 틀렸는지는 errors[]로만 알 수 있다.
    mockMvc
        .perform(register(landlordToken(), bodyReplacing("\"1~2\"", "\"1-2\"")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
        .andExpect(jsonPath("$.error.errors[0].field").value("building.usedFloorRange"))
        .andDo(
            errorSnippet(
                "listing-register-invalid-input",
                ApiDocsTags.LISTINGS,
                LISTING_REGISTER_SUMMARY,
                LISTING_REGISTER_DESCRIPTION,
                LISTING_REGISTER_400));

    // 좌표를 빼면 필드를 특정할 수 있으므로 errors[]에 실린다.
    mockMvc
        .perform(register(landlordToken(), bodyWithoutCoordinates()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
        // 두 좌표가 함께 빠지므로 순서를 가정하지 않는다 — Bean Validation의 위반 순서는 보장되지 않는다.
        .andExpect(
            jsonPath("$.error.errors[*].field")
                .value(containsInAnyOrder("address.lat", "address.lng")))
        .andDo(
            errorSnippet(
                "listing-register-missing-coordinates",
                ApiDocsTags.LISTINGS,
                LISTING_REGISTER_SUMMARY,
                LISTING_REGISTER_DESCRIPTION,
                LISTING_REGISTER_400));

    // WGS84 범위를 벗어난 좌표. 검색 결과가 아닌 값을 만들어 보낸 경우다.
    performError(
        register(landlordToken(), bodyReplacing("37.5559918", "137.5559918")),
        status().isBadRequest(),
        "INVALID_INPUT",
        "listing-register-invalid-coordinates",
        LISTING_REGISTER_400);

    // 앱이 아는 코드가 서버 코드표에는 아직 없는 상황을 카탈로그에서 한 행을 지워 재현한다.
    mongoTemplate
        .getCollection(LISTING_CATALOG_COLLECTION)
        .deleteOne(new Document("_id", NO_MAINT_FEE_CATALOG_ID));
    performError(
        register(landlordToken(), bodyReplacing("\"ADDRESS_REGISTRATION\"", "\"NO_MAINT_FEE\"")),
        status().isBadRequest(),
        "LISTING_UNKNOWN_CATALOG_CODE",
        "listing-register-unknown-catalog-code",
        LISTING_REGISTER_400);

    // NONE 은 카탈로그에 실재하는 정상 코드라 코드 대조를 그대로 통과한다 — 단독 규칙을 따로 막지 않으면
    // ["NONE","WIFI"] 가 저장돼 응답에 「없음, 와이파이」로 나간다.
    performError(
        register(landlordToken(), bodyReplacing("[\"WIFI\", \"TV\"]", "[\"NONE\", \"WIFI\"]")),
        status().isBadRequest(),
        "INVALID_INPUT",
        "listing-register-none-not-alone",
        LISTING_REGISTER_400);

    performError(
        register(landlordToken(), MALFORMED_BODY),
        status().isBadRequest(),
        "MALFORMED_REQUEST",
        "listing-register-malformed",
        LISTING_REGISTER_400);
  }

  /**
   * 설문 2종은 선택이라 키를 아예 빼도 등록된다(#270).
   *
   * <p><b>응답으로는 증명할 수 없다</b> — 등록 응답은 설문을 감추기 때문이다(위 {@code doesNotExist} 단정). 그래서 저장 문서를 직접 읽어 「키가
   * 있고 빈 배열」임을 본다. 키가 사라지면 MongoDB {@code required} 위반이라 dev에서만 500이 나는데, 테스트 프로파일은 validator를 적용하지
   * 않아 여기서 값을 직접 보지 않으면 아무도 잡지 못한다.
   */
  @Test
  @DisplayName("설문 2종을 생략해도 등록되고 저장은 빈 배열이다")
  void 설문생략_등록성공_빈배열저장() throws Exception {
    String body =
        REGISTER_BODY
            .replaceAll("(?m)^ *\"preferredNationalities\".*\\R", "")
            .replaceAll("(?m)^ *\"contractDifficulties\".*\\R", "");
    if (body.contains("preferredNationalities") || body.contains("contractDifficulties")) {
      throw new IllegalStateException("등록 본문에서 설문 두 필드를 빼지 못했다");
    }

    mockMvc.perform(register(landlordToken(), body)).andExpect(status().isCreated());

    Document saved = mongoTemplate.getCollection(LISTINGS_COLLECTION).find().first();
    assertThat(saved).isNotNull();
    assertThat(saved.getList("preferredNationalities", String.class)).isEmpty();
    assertThat(saved.getList("contractDifficulties", String.class)).isEmpty();
  }

  /**
   * 시설이 하나도 없는 건물도 등록된다(#270). 등록 응답은 시설을 감추지 않지만 세입자 상세와 같은 모양이라 여기서 {@code NONE} 이 code/label 로
   * 나가는 것까지 함께 본다.
   *
   * <p>문서 스니펫은 만들지 않는다 — 같은 오퍼레이션의 성공 예시는 한 벌이고, 이 케이스는 계약 회귀만 지킨다.
   */
  @Test
  @DisplayName("시설이 하나도 없으면 NONE 하나만 보내 등록할 수 있다")
  void 시설없음_NONE_단독_등록() throws Exception {
    String body =
        bodyReplacing("[\"WASHER\", \"DRYING_RACK\"]", "[\"NONE\"]")
            .replace("[\"CONVENIENCE_STORE\", \"HOSPITAL_PHARMACY\"]", "[\"NONE\"]");

    mockMvc
        .perform(register(landlordToken(), body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.facilities.laundry.length()").value(1))
        .andExpect(jsonPath("$.data.facilities.laundry[0].code").value("NONE"))
        .andExpect(jsonPath("$.data.facilities.laundry[0].label").value("없음"))
        .andExpect(jsonPath("$.data.nearbyFacilities[0].code").value("NONE"));
  }

  /** 등록에 성공하고 발급된 listingId를 돌려준다. 스니펫은 만들지 않는다(성공 오퍼레이션 예시는 한 벌이면 된다). */
  private String registerListing() throws Exception {
    String body =
        mockMvc
            .perform(register(landlordToken(), REGISTER_BODY))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String listingId = objectMapper.readTree(body).path("data").path("listingId").asText();
    assertThat(listingId).isNotBlank();
    return listingId;
  }

  private void performError(
      MockHttpServletRequestBuilder request,
      ResultMatcher expectedStatus,
      String expectedCode,
      String identifier,
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
                ApiDocsTags.LISTINGS,
                LISTING_REGISTER_SUMMARY,
                LISTING_REGISTER_DESCRIPTION,
                errorCodes));
  }

  private MockHttpServletRequestBuilder register(String bearerToken, String body) {
    return post("/api/v2/listings")
        .header(HttpHeaders.AUTHORIZATION, bearerToken)
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }

  /** 본문 없이 보내는 요청. 토큰 자체가 거절되는 401·403에는 본문이 필요 없다. */
  private MockHttpServletRequestBuilder emptyRegister() {
    return post("/api/v2/listings").contentType(MediaType.APPLICATION_JSON);
  }

  /** 좌표만 뺀 변형이다. 주소 검색을 건너뛰고 제출한 요청을 재현한다. */
  private static String bodyWithoutCoordinates() {
    String withCoordinates =
        """
        {
            "fullAddress": "서울특별시 서대문구 신촌로 12",
            "detail": "3층 305호",
            "lat": 37.5559918,
            "lng": 126.9368647
          }""";
    String withoutCoordinates =
        """
        {
            "fullAddress": "서울특별시 서대문구 신촌로 12",
            "detail": "3층 305호"
          }""";
    if (!REGISTER_BODY.contains(withCoordinates)) {
      throw new IllegalStateException("등록 본문의 address 블록을 찾지 못했다");
    }
    return REGISTER_BODY.replace(withCoordinates, withoutCoordinates);
  }

  /** 정상 본문에서 한 값만 바꾼 변형이다. 본문에서 유일하게 지목할 수 있는 값만 바꾼다. */
  private static String bodyReplacing(String original, String replacement) {
    int first = REGISTER_BODY.indexOf(original);
    if (first < 0 || first != REGISTER_BODY.lastIndexOf(original)) {
      throw new IllegalArgumentException("등록 본문에서 유일하게 지목할 수 없는 값이다: " + original);
    }
    return REGISTER_BODY.replace(original, replacement);
  }

  private String landlordToken() {
    return bearer(jwtTokenService.issueAccessToken(LANDLORD_ID));
  }

  private String tenantToken() {
    return bearer(jwtTokenService.issueAccessToken(TENANT_ID));
  }
}
