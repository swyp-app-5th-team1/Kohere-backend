package com.kohere.booking;

import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.document;
import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.resourceDetails;
import static com.kohere.docs.BookingDocsFields.CREATE_400;
import static com.kohere.docs.BookingDocsFields.CREATE_401;
import static com.kohere.docs.BookingDocsFields.CREATE_403;
import static com.kohere.docs.BookingDocsFields.CREATE_404;
import static com.kohere.docs.BookingDocsFields.CREATE_409;
import static com.kohere.docs.BookingDocsFields.CREATE_422;
import static com.kohere.docs.BookingDocsFields.CREATE_DESCRIPTION;
import static com.kohere.docs.BookingDocsFields.CREATE_SUMMARY;
import static com.kohere.docs.BookingDocsFields.DETAIL_401;
import static com.kohere.docs.BookingDocsFields.DETAIL_403;
import static com.kohere.docs.BookingDocsFields.DETAIL_404;
import static com.kohere.docs.BookingDocsFields.DETAIL_DESCRIPTION;
import static com.kohere.docs.BookingDocsFields.DETAIL_SUMMARY;
import static com.kohere.docs.BookingDocsFields.LIST_400;
import static com.kohere.docs.BookingDocsFields.LIST_401;
import static com.kohere.docs.BookingDocsFields.LIST_403;
import static com.kohere.docs.BookingDocsFields.LIST_DESCRIPTION;
import static com.kohere.docs.BookingDocsFields.LIST_SUMMARY;
import static com.kohere.docs.BookingDocsFields.createPathParameters;
import static com.kohere.docs.BookingDocsFields.createRequestFields;
import static com.kohere.docs.BookingDocsFields.createResponseFields;
import static com.kohere.docs.BookingDocsFields.createResponseHeaders;
import static com.kohere.docs.BookingDocsFields.detailPathParameters;
import static com.kohere.docs.BookingDocsFields.detailResponseFields;
import static com.kohere.docs.BookingDocsFields.listQueryParameters;
import static com.kohere.docs.BookingDocsFields.listResponseFields;
import static com.kohere.docs.DocsTokens.bearer;
import static com.kohere.docs.DocsTokens.expiredAccessToken;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.post;
import static org.springframework.restdocs.payload.PayloadDocumentation.requestFields;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.restdocs.request.RequestDocumentation.pathParameters;
import static org.springframework.restdocs.request.RequestDocumentation.queryParameters;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kohere.TestcontainersConfiguration;
import com.kohere.common.security.JwtProperties;
import com.kohere.common.security.JwtTokenService;
import com.kohere.docs.ApiDocsErrors;
import com.kohere.docs.ApiDocsTags;
import com.kohere.docs.BookingDocsFields;
import com.kohere.listing.api.BookingListingQueryService;
import com.kohere.listing.api.RoomOfferBookingView;
import com.kohere.user.api.ApplicantProfileView;
import com.kohere.user.api.UserAccountService;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * 매물 예약(신청) API 문서화 + 통합 테스트. docs/api/specs/04-booking-inquiry-chat.md §1~§3. 예약 생성·내 예약 목록·단건 상세와
 * 주요 에러(403 비세입자·404 매물/예약 부재·422 입주일·400 입력)를 검증하고 restdocs-api-spec 스니펫(OpenAPI)을 생성한다.
 *
 * <p>예약 저장은 실제 MySQL(Testcontainers, JPA)에 하고, 교차 모듈 협력({@code listing :: api}·{@code user ::
 * api})은 {@link MockitoBean}으로 대체한다(listing::api는 스텁 미구현). 인증은 실제 access 토큰(ROLE_USER)을 발급해 사용한다.
 *
 * <p>오퍼레이션 문구·필드 기술자는 {@link BookingDocsFields}에 한 벌만 두고 여기서 참조한다 — 생성({@code POST
 * /api/v1/listings/{listingId}/bookings})의 차단 403 스니펫이 {@code BookingManagementDocsTest}에 있어 한
 * 오퍼레이션이 두 파일에 걸치기 때문이다(#151).
 */
@SpringBootTest
@ExtendWith(RestDocumentationExtension.class)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional // 동일 (tenant, roomOffer) 중복 방지 유니크 도입 후, 테스트 간 예약 누적을 롤백으로 격리
class BookingDocsTest {

  private static final long TENANT_ID = 1L;
  private static final long LANDLORD_ID = 42L;
  private static final String LISTING_ID = "6858e2000000000000000001";
  private static final String ROOM_OFFER_ID = "6858e2000000000000000abc";
  private static final Pattern BOOKING_ID = Pattern.compile("\"bookingId\"\\s*:\\s*(\\d+)");

  @Autowired private WebApplicationContext context;
  @Autowired private JwtTokenService jwtTokenService;
  @Autowired private JwtProperties jwtProperties;

  @MockitoBean private UserAccountService userAccountService;
  @MockitoBean private BookingListingQueryService listingQueryService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp(RestDocumentationContextProvider restDocumentation) {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .apply(springSecurity())
            .apply(documentationConfiguration(restDocumentation))
            .build();
    // 예약 성공 시 BOOKING_CARD 사본을 함께 만들므로 모든 성공 테스트가 동일한 신청자 공개 뷰를 사용한다.
    given(userAccountService.getApplicantProfile(TENANT_ID)).willReturn(applicantView());
  }

  /** Swagger 예약 예시와 비동기 신청 카드가 함께 사용하는 신청자 정보다. */
  private ApplicantProfileView applicantView() {
    return new ApplicantProfileView(
        TENANT_ID, "Gil dong Hong", "MALE", "MN", "Mongolia", "kohere@gmail.com");
  }

  private String tenantToken() {
    return "Bearer " + jwtTokenService.issueAccessToken(TENANT_ID);
  }

  private RoomOfferBookingView offerView() {
    return new RoomOfferBookingView(
        LISTING_ID,
        ROOM_OFFER_ID,
        "강남역 도보 5분 원룸",
        "https://cdn.kohere.com/listings/" + LISTING_ID + "/thumb.jpg",
        "서울특별시 강남구 테헤란로 1",
        "101호 원룸",
        5_000_000,
        500_000,
        LANDLORD_ID);
  }

  private String landlordToken() {
    return "Bearer " + jwtTokenService.issueAccessToken(LANDLORD_ID);
  }

  private static String createRequestJson(String roomOfferId, String moveInDate, Integer months) {
    return String.format(
        "{\"roomOfferId\":%s,\"moveInDate\":%s,\"contractPeriod\":%s}",
        roomOfferId == null ? "null" : "\"" + roomOfferId + "\"",
        moveInDate == null ? "null" : "\"" + moveInDate + "\"",
        months == null ? "null" : months);
  }

  private long createBooking() throws Exception {
    given(userAccountService.getUserType(TENANT_ID)).willReturn("TENANT");
    given(listingQueryService.findPublishedRoomOffer(LISTING_ID, ROOM_OFFER_ID))
        .willReturn(Optional.of(offerView()));
    String body =
        mockMvc
            .perform(
                post("/api/v1/listings/{listingId}/bookings", LISTING_ID)
                    .header(HttpHeaders.AUTHORIZATION, tenantToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createRequestJson(ROOM_OFFER_ID, "2030-01-01", 6)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    Matcher m = BOOKING_ID.matcher(body);
    if (!m.find()) {
      throw new IllegalStateException("bookingId not found in response: " + body);
    }
    return Long.parseLong(m.group(1));
  }

  // ── §1 예약 생성 ─────────────────────────────────────────────
  @Test
  void createBooking_success() throws Exception {
    given(userAccountService.getUserType(TENANT_ID)).willReturn("TENANT");
    given(listingQueryService.findPublishedRoomOffer(LISTING_ID, ROOM_OFFER_ID))
        .willReturn(Optional.of(offerView()));

    mockMvc
        .perform(
            post("/api/v1/listings/{listingId}/bookings", LISTING_ID)
                .header(HttpHeaders.AUTHORIZATION, tenantToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(createRequestJson(ROOM_OFFER_ID, "2030-01-01", 6)))
        .andExpect(status().isCreated())
        .andExpect(header().exists(HttpHeaders.LOCATION))
        .andExpect(jsonPath("$.data.status").value("REQUESTED"))
        .andExpect(jsonPath("$.data.roomOfferId").value(ROOM_OFFER_ID))
        .andExpect(jsonPath("$.data.contractPeriod").value(6))
        .andDo(
            document(
                "booking-create",
                resourceDetails()
                    .tag(ApiDocsTags.BOOKINGS)
                    .summary(CREATE_SUMMARY)
                    .description(CREATE_DESCRIPTION),
                pathParameters(createPathParameters()),
                requestFields(createRequestFields()),
                createResponseHeaders(),
                responseFields(createResponseFields())));
  }

  @Test
  void createBooking_duplicate_conflict() throws Exception {
    createBooking(); // 동일 (tenant, roomOffer) 첫 신청
    given(userAccountService.getUserType(TENANT_ID)).willReturn("TENANT");
    given(listingQueryService.findPublishedRoomOffer(LISTING_ID, ROOM_OFFER_ID))
        .willReturn(Optional.of(offerView()));

    performCreateError(
        post("/api/v1/listings/{listingId}/bookings", LISTING_ID)
            .header(HttpHeaders.AUTHORIZATION, tenantToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content(createRequestJson(ROOM_OFFER_ID, "2030-01-01", 6)),
        status().isConflict(),
        "BOOKING_ALREADY_EXISTS",
        "booking-create-duplicate",
        CREATE_409);
  }

  // ── §2 내 예약 목록 ───────────────────────────────────────────
  @Test
  void getMyBookings_success() throws Exception {
    createBooking();
    given(listingQueryService.findPublishedRoomOffer(anyString(), anyString()))
        .willReturn(Optional.of(offerView()));

    mockMvc
        .perform(
            get("/api/v1/bookings")
                .header(HttpHeaders.AUTHORIZATION, tenantToken())
                .param("page", "0")
                .param("size", "20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content").isArray())
        .andExpect(jsonPath("$.data.content[0].bookingId").exists())
        // 규약 13 — 임대인 전용 키를 합집합 스키마에서 optional로 낮춘 대가로, 세입자 응답에 정말 없음을 단정한다.
        .andExpect(jsonPath("$.data.content[0].roomOfferName").doesNotExist())
        .andExpect(jsonPath("$.data.content[0].applicantName").doesNotExist())
        .andDo(
            document(
                "booking-list-tenant",
                resourceDetails()
                    .tag(ApiDocsTags.BOOKINGS)
                    .summary(LIST_SUMMARY)
                    .description(LIST_DESCRIPTION),
                queryParameters(listQueryParameters()),
                responseFields(listResponseFields())));
  }

  // ── §3 예약 단건 상세 ─────────────────────────────────────────
  @Test
  void getBooking_success() throws Exception {
    long bookingId = createBooking();
    given(listingQueryService.findPublishedRoomOffer(LISTING_ID, ROOM_OFFER_ID))
        .willReturn(Optional.of(offerView()));
    given(userAccountService.getUserName(TENANT_ID)).willReturn("길동 홍");

    mockMvc
        .perform(
            get("/api/v1/bookings/{bookingId}", bookingId)
                .header(HttpHeaders.AUTHORIZATION, tenantToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.bookingId").value(bookingId))
        .andExpect(jsonPath("$.data.deposit").value(5_000_000))
        .andExpect(jsonPath("$.data.totalAmount").value(8_000_000)) // 5,000,000 + 500,000 × 6
        .andExpect(jsonPath("$.data.tenantName").value("길동 홍"))
        // 규약 13 — 임대인 전용 키(신청자 PII 포함)를 optional로 낮춘 대가로, 세입자 응답에 정말 없음을 단정한다.
        .andExpect(jsonPath("$.data.applicantId").doesNotExist())
        .andExpect(jsonPath("$.data.applicantName").doesNotExist())
        .andExpect(jsonPath("$.data.applicantGender").doesNotExist())
        .andExpect(jsonPath("$.data.applicantCountry").doesNotExist())
        .andExpect(jsonPath("$.data.applicantCountryName").doesNotExist())
        .andExpect(jsonPath("$.data.applicantEmail").doesNotExist())
        .andDo(
            document(
                "booking-detail-tenant",
                resourceDetails()
                    .tag(ApiDocsTags.BOOKINGS)
                    .summary(DETAIL_SUMMARY)
                    .description(DETAIL_DESCRIPTION),
                pathParameters(detailPathParameters()),
                responseFields(detailResponseFields())));
  }

  // ── §2 임대인 분기 — 받은 신청 목록 ───────────────────────────
  @Test
  void getBookings_landlord_success() throws Exception {
    createBooking(); // 생성 시 landlord_id = LANDLORD_ID(offerView) 저장
    given(userAccountService.getUserType(LANDLORD_ID)).willReturn("LANDLORD");
    given(listingQueryService.findPublishedRoomOffer(anyString(), anyString()))
        .willReturn(Optional.of(offerView()));
    given(userAccountService.getUserName(TENANT_ID)).willReturn("길동 홍");

    mockMvc
        .perform(
            get("/api/v1/bookings")
                .header(HttpHeaders.AUTHORIZATION, landlordToken())
                .param("page", "0")
                .param("size", "20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[0].applicantName").value("길동 홍"))
        .andExpect(jsonPath("$.data.content[0].roomOfferName").value("101호 원룸"))
        .andDo(
            document(
                "booking-list-landlord",
                resourceDetails()
                    .tag(ApiDocsTags.BOOKINGS)
                    .summary(LIST_SUMMARY)
                    .description(LIST_DESCRIPTION),
                queryParameters(listQueryParameters()),
                responseFields(listResponseFields())));
  }

  // ── §3 임대인 분기 — 받은 신청 단건 상세 ──────────────────────
  @Test
  void getBooking_landlord_success() throws Exception {
    long bookingId = createBooking();
    given(userAccountService.getUserType(LANDLORD_ID)).willReturn("LANDLORD");
    given(listingQueryService.findPublishedRoomOffer(LISTING_ID, ROOM_OFFER_ID))
        .willReturn(Optional.of(offerView()));
    given(userAccountService.getApplicantProfile(TENANT_ID))
        .willReturn(
            new ApplicantProfileView(
                TENANT_ID, "길동 홍", "MALE", "US", "United States", "hong@example.com"));

    mockMvc
        .perform(
            get("/api/v1/bookings/{bookingId}", bookingId)
                .header(HttpHeaders.AUTHORIZATION, landlordToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.bookingId").value(bookingId))
        .andExpect(jsonPath("$.data.applicantName").value("길동 홍"))
        .andExpect(jsonPath("$.data.applicantEmail").value("hong@example.com"))
        .andExpect(jsonPath("$.data.totalAmount").value(8_000_000)) // 5,000,000 + 500,000 × 6
        // 규약 13 — 세입자 전용 키를 optional로 낮춘 대가로, 임대인 응답에 정말 없음을 단정한다.
        .andExpect(jsonPath("$.data.tenantName").doesNotExist())
        .andDo(
            document(
                "booking-detail-landlord",
                resourceDetails()
                    .tag(ApiDocsTags.BOOKINGS)
                    .summary(DETAIL_SUMMARY)
                    .description(DETAIL_DESCRIPTION),
                pathParameters(detailPathParameters()),
                responseFields(detailResponseFields())));
  }

  // ── 에러 ─────────────────────────────────────────────────────
  @Test
  void createBooking_nonTenant_forbidden() throws Exception {
    given(userAccountService.getUserType(TENANT_ID)).willReturn("LANDLORD");

    performCreateError(
        post("/api/v1/listings/{listingId}/bookings", LISTING_ID)
            .header(HttpHeaders.AUTHORIZATION, tenantToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content(createRequestJson(ROOM_OFFER_ID, "2030-01-01", 6)),
        status().isForbidden(),
        "FORBIDDEN",
        "booking-create-forbidden",
        CREATE_403);
  }

  @Test
  void createBooking_listingNotFound() throws Exception {
    given(userAccountService.getUserType(TENANT_ID)).willReturn("TENANT");
    given(listingQueryService.findPublishedRoomOffer(anyString(), anyString()))
        .willReturn(Optional.empty());

    performCreateError(
        post("/api/v1/listings/{listingId}/bookings", LISTING_ID)
            .header(HttpHeaders.AUTHORIZATION, tenantToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content(createRequestJson(ROOM_OFFER_ID, "2030-01-01", 6)),
        status().isNotFound(),
        "LISTING_NOT_FOUND",
        "booking-create-listing-not-found",
        CREATE_404);
  }

  @Test
  void createBooking_pastMoveInDate_unprocessable() throws Exception {
    given(userAccountService.getUserType(TENANT_ID)).willReturn("TENANT");
    given(listingQueryService.findPublishedRoomOffer(LISTING_ID, ROOM_OFFER_ID))
        .willReturn(Optional.of(offerView()));

    performCreateError(
        post("/api/v1/listings/{listingId}/bookings", LISTING_ID)
            .header(HttpHeaders.AUTHORIZATION, tenantToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content(createRequestJson(ROOM_OFFER_ID, "2020-01-01", 6)),
        status().isUnprocessableEntity(),
        "BOOKING_INVALID_MOVE_IN_DATE",
        "booking-create-invalid-move-in-date",
        CREATE_422);
  }

  @Test
  void createBooking_missingRoomOfferId_invalidInput() throws Exception {
    given(userAccountService.getUserType(TENANT_ID)).willReturn("TENANT");

    performCreateError(
        post("/api/v1/listings/{listingId}/bookings", LISTING_ID)
            .header(HttpHeaders.AUTHORIZATION, tenantToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content(createRequestJson(null, "2030-01-01", 6)),
        status().isBadRequest(),
        "INVALID_INPUT",
        "booking-create-invalid-input",
        CREATE_400);
  }

  @Test
  void createBooking_unauthenticated() throws Exception {
    performCreateError(
        post("/api/v1/listings/{listingId}/bookings", LISTING_ID)
            .contentType(MediaType.APPLICATION_JSON),
        status().isUnauthorized(),
        "UNAUTHENTICATED",
        "booking-create-unauthenticated",
        CREATE_401);
  }

  @Test
  void getBooking_notFound() throws Exception {
    perform(
        get("/api/v1/bookings/{bookingId}", 999_999L)
            .header(HttpHeaders.AUTHORIZATION, tenantToken()),
        status().isNotFound(),
        "BOOKING_NOT_FOUND",
        "booking-detail-not-found",
        DETAIL_SUMMARY,
        DETAIL_DESCRIPTION,
        detailPathParameters(),
        DETAIL_404);
  }

  /**
   * 목록·상세의 인증/입력 실패 스니펫. 전부 보안 필터 또는 MVC 바인딩 단계에서 끝나 예약 상태가 필요 없으므로 한 테스트에 모은다.
   *
   * <p>{@code 400}은 두 갈래라 스니펫도 둘이다 — 범위 위반({@code size=101})은 {@code BookingService.validatePage}가
   * 던지는 {@code INVALID_INPUT}, 정수가 아닌 값({@code page=abc})은 바인딩 단계의 {@code MALFORMED_REQUEST}다.
   */
  @Test
  void listAndDetail_errorSnippets() throws Exception {
    String onboardingToken = bearer(jwtTokenService.issueOnboardingToken(TENANT_ID));
    String expiredToken = bearer(expiredAccessToken(jwtProperties));

    performListError(
        get("/api/v1/bookings")
            .header(HttpHeaders.AUTHORIZATION, tenantToken())
            .param("size", "101"),
        status().isBadRequest(),
        "INVALID_INPUT",
        "booking-list-invalid-input",
        LIST_400);
    performListError(
        get("/api/v1/bookings")
            .header(HttpHeaders.AUTHORIZATION, tenantToken())
            .param("page", "abc"),
        status().isBadRequest(),
        "MALFORMED_REQUEST",
        "booking-list-malformed-request",
        LIST_400);
    performListError(
        get("/api/v1/bookings"),
        status().isUnauthorized(),
        "UNAUTHENTICATED",
        "booking-list-unauthenticated",
        LIST_401);
    performListError(
        get("/api/v1/bookings").header(HttpHeaders.AUTHORIZATION, expiredToken),
        status().isUnauthorized(),
        "TOKEN_EXPIRED",
        "booking-list-token-expired",
        LIST_401);
    performListError(
        get("/api/v1/bookings").header(HttpHeaders.AUTHORIZATION, onboardingToken),
        status().isForbidden(),
        "AUTH_ONBOARDING_REQUIRED",
        "booking-list-onboarding-required",
        LIST_403);

    performDetailError(
        get("/api/v1/bookings/{bookingId}", 1L),
        status().isUnauthorized(),
        "UNAUTHENTICATED",
        "booking-detail-unauthenticated",
        DETAIL_401);
    performDetailError(
        get("/api/v1/bookings/{bookingId}", 1L).header(HttpHeaders.AUTHORIZATION, expiredToken),
        status().isUnauthorized(),
        "TOKEN_EXPIRED",
        "booking-detail-token-expired",
        DETAIL_401);
    performDetailError(
        get("/api/v1/bookings/{bookingId}", 1L).header(HttpHeaders.AUTHORIZATION, onboardingToken),
        status().isForbidden(),
        "AUTH_ONBOARDING_REQUIRED",
        "booking-detail-onboarding-required",
        DETAIL_403);
  }

  /** 예약 생성 오퍼레이션의 실패 스니펫 — 문구·path 파라미터가 성공 스니펫과 동일해야 한다. */
  private void performCreateError(
      MockHttpServletRequestBuilder request,
      ResultMatcher expectedStatus,
      String expectedCode,
      String identifier,
      String... errorCodes)
      throws Exception {
    perform(
        request,
        expectedStatus,
        expectedCode,
        identifier,
        CREATE_SUMMARY,
        CREATE_DESCRIPTION,
        createPathParameters(),
        errorCodes);
  }

  /**
   * 목록 오퍼레이션의 실패 스니펫. path 변수가 없어 {@code pathParameters}를 넘기지 않는다 — 쿼리 파라미터는 생성기가 같은 {@code (path,
   * method)} 모델 전체를 {@code flatMap} + {@code distinctBy(name)}로 합치므로 성공 스니펫의 선언이 그대로 살아남는다(path 변수만
   * 첫 모델에서 가져온다).
   */
  private void performListError(
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
            ApiDocsErrors.errorSnippet(
                identifier, ApiDocsTags.BOOKINGS, LIST_SUMMARY, LIST_DESCRIPTION, errorCodes));
  }

  private void performDetailError(
      MockHttpServletRequestBuilder request,
      ResultMatcher expectedStatus,
      String expectedCode,
      String identifier,
      String... errorCodes)
      throws Exception {
    perform(
        request,
        expectedStatus,
        expectedCode,
        identifier,
        DETAIL_SUMMARY,
        DETAIL_DESCRIPTION,
        detailPathParameters(),
        errorCodes);
  }

  private void perform(
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
            ApiDocsErrors.errorSnippet(
                identifier,
                ApiDocsTags.BOOKINGS,
                summary,
                description,
                pathParameters,
                errorCodes));
  }
}
