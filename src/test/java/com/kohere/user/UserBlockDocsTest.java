package com.kohere.user;

import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.document;
import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.resourceDetails;
import static com.kohere.docs.DocsTokens.bearer;
import static com.kohere.docs.DocsTokens.expiredAccessToken;
import static com.kohere.docs.UserDocsFields.BLOCKS_LIST_400;
import static com.kohere.docs.UserDocsFields.BLOCKS_LIST_401;
import static com.kohere.docs.UserDocsFields.BLOCKS_LIST_403;
import static com.kohere.docs.UserDocsFields.BLOCKS_LIST_DESCRIPTION;
import static com.kohere.docs.UserDocsFields.BLOCKS_LIST_SUMMARY;
import static com.kohere.docs.UserDocsFields.UNBLOCK_400;
import static com.kohere.docs.UserDocsFields.UNBLOCK_401;
import static com.kohere.docs.UserDocsFields.UNBLOCK_403;
import static com.kohere.docs.UserDocsFields.UNBLOCK_DESCRIPTION;
import static com.kohere.docs.UserDocsFields.UNBLOCK_SUMMARY;
import static com.kohere.docs.UserDocsFields.blocksListQueryParameters;
import static com.kohere.docs.UserDocsFields.blocksListResponseFields;
import static com.kohere.docs.UserDocsFields.unblockPathParameters;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.delete;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.post;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.restdocs.request.RequestDocumentation.pathParameters;
import static org.springframework.restdocs.request.RequestDocumentation.queryParameters;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kohere.TestcontainersConfiguration;
import com.kohere.common.security.JwtProperties;
import com.kohere.common.security.JwtTokenService;
import com.kohere.docs.ApiDocsErrors;
import com.kohere.docs.ApiDocsTags;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * 내 차단 목록·해제 API 문서화 + 통합 테스트(/api/v1/users/me/blocks). docs/api/specs/01-auth-onboarding.md
 * §11·§12.
 *
 * <p>차단 생성은 예약 문맥(POST /api/v1/bookings/{id}/block)에서만 가능하므로, 예약 생성→차단으로 상태를 만든 뒤 목록·해제를 검증한다. 실제
 * 리포지토리에 기록되므로 {@link Transactional}로 각 메서드 종료 시 롤백해 격리한다.
 */
@SpringBootTest
@ExtendWith(RestDocumentationExtension.class)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class UserBlockDocsTest {

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
    // 차단 목록·해제에 세입자·임대인 허용 목록 게이트가 붙었다. userType 을 스텁하지 않으면 mock 이
    // null 을 돌려줘 403 이 나고, 정작 검증하려던 차단 동작에 닿지 못한다.
    lenient().when(userAccountService.getUserType(anyLong())).thenReturn("TENANT");
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .apply(springSecurity())
            .apply(documentationConfiguration(restDocumentation))
            .build();
  }

  private String token(long userId) {
    return "Bearer " + jwtTokenService.issueAccessToken(userId);
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

  /** 세입자가 예약을 만들고 그 상대(임대인)를 차단한다 — 차단 상대 식별자 = LANDLORD_ID. */
  private void blockLandlordViaBooking() throws Exception {
    given(userAccountService.getUserType(TENANT_ID)).willReturn("TENANT");
    // 예약 생성은 이제 신청 시점의 BOOKING_CARD 사본도 함께 만든다. 이 테스트의 관심사는 차단이지만,
    // 실제 예약 생성 경로를 통과하므로 신청자 공개 정보도 정상 값으로 준비한다.
    given(userAccountService.getApplicantProfile(TENANT_ID))
        .willReturn(
            new ApplicantProfileView(
                TENANT_ID, "테스트 세입자", "OTHER", "KR", "대한민국", "tenant@example.com"));
    given(listingQueryService.findPublishedRoomOffer(anyString(), anyString()))
        .willReturn(Optional.of(offerView()));
    given(listingQueryService.findRoomOfferForExistingBooking(anyString(), anyString()))
        .willReturn(Optional.of(offerView()));
    String body =
        mockMvc
            .perform(
                post("/api/v1/listings/{listingId}/bookings", LISTING_ID)
                    .header(HttpHeaders.AUTHORIZATION, token(TENANT_ID))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"roomOfferId\":\""
                            + ROOM_OFFER_ID
                            + "\",\"moveInDate\":\"2030-01-01\",\"contractPeriod\":6}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    Matcher m = BOOKING_ID.matcher(body);
    if (!m.find()) {
      throw new IllegalStateException("bookingId not found: " + body);
    }
    long bookingId = Long.parseLong(m.group(1));
    mockMvc
        .perform(
            post("/api/v1/bookings/{bookingId}/block", bookingId)
                .header(HttpHeaders.AUTHORIZATION, token(TENANT_ID)))
        .andExpect(status().isNoContent());
  }

  @Test
  void getMyBlocks_success() throws Exception {
    blockLandlordViaBooking();

    mockMvc
        .perform(
            get("/api/v1/users/me/blocks")
                .header(HttpHeaders.AUTHORIZATION, token(TENANT_ID))
                .param("page", "0")
                .param("size", "20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[0].userId").value(LANDLORD_ID))
        .andDo(
            document(
                "user-blocks-list",
                resourceDetails()
                    .tag(ApiDocsTags.USERS)
                    .summary(BLOCKS_LIST_SUMMARY)
                    .description(BLOCKS_LIST_DESCRIPTION),
                queryParameters(blocksListQueryParameters()),
                responseFields(blocksListResponseFields())));
  }

  @Test
  void unblock_success() throws Exception {
    blockLandlordViaBooking();

    mockMvc
        .perform(
            delete("/api/v1/users/me/blocks/{userId}", LANDLORD_ID)
                .header(HttpHeaders.AUTHORIZATION, token(TENANT_ID)))
        .andExpect(status().isNoContent())
        .andDo(
            document(
                "user-blocks-unblock",
                resourceDetails()
                    .tag(ApiDocsTags.USERS)
                    .summary(UNBLOCK_SUMMARY)
                    .description(UNBLOCK_DESCRIPTION),
                pathParameters(unblockPathParameters())));

    mockMvc
        .perform(get("/api/v1/users/me/blocks").header(HttpHeaders.AUTHORIZATION, token(TENANT_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content").isEmpty());
  }

  @Test
  void unblock_idempotent() throws Exception {
    // 차단하지 않은 상대를 해제해도 204(멱등)
    mockMvc
        .perform(
            delete("/api/v1/users/me/blocks/{userId}", LANDLORD_ID)
                .header(HttpHeaders.AUTHORIZATION, token(TENANT_ID)))
        .andExpect(status().isNoContent());
  }

  /**
   * 목록·해제의 실패 스니펫. 전부 보안 필터 또는 MVC 바인딩에서 끝나 차단 상태가 필요 없다.
   *
   * <p>{@code 400}은 두 갈래라 스니펫도 둘이다 — 범위 위반({@code size=101})은 {@code
   * UserBlockServiceImpl.validatePage}가 던지는 {@code INVALID_INPUT}, 정수가 아닌 값은 바인딩 단계의 {@code
   * MALFORMED_REQUEST}다. 해제는 멱등이라 차단하지 않은 상대에도 204를 주므로 404가 없다.
   */
  @Test
  void blocksErrorSnippets() throws Exception {
    String onboardingToken = bearer(jwtTokenService.issueOnboardingToken(TENANT_ID));
    String expiredToken = bearer(expiredAccessToken(jwtProperties));

    performListError(
        get("/api/v1/users/me/blocks")
            .header(HttpHeaders.AUTHORIZATION, token(TENANT_ID))
            .param("size", "101"),
        status().isBadRequest(),
        "INVALID_INPUT",
        "user-blocks-list-invalid-input",
        BLOCKS_LIST_400);
    performListError(
        get("/api/v1/users/me/blocks")
            .header(HttpHeaders.AUTHORIZATION, token(TENANT_ID))
            .param("size", "abc"),
        status().isBadRequest(),
        "MALFORMED_REQUEST",
        "user-blocks-list-malformed-request",
        BLOCKS_LIST_400);
    performListError(
        get("/api/v1/users/me/blocks"),
        status().isUnauthorized(),
        "UNAUTHENTICATED",
        "user-blocks-list-unauthenticated",
        BLOCKS_LIST_401);
    performListError(
        get("/api/v1/users/me/blocks").header(HttpHeaders.AUTHORIZATION, expiredToken),
        status().isUnauthorized(),
        "TOKEN_EXPIRED",
        "user-blocks-list-token-expired",
        BLOCKS_LIST_401);
    performListError(
        get("/api/v1/users/me/blocks").header(HttpHeaders.AUTHORIZATION, onboardingToken),
        status().isForbidden(),
        "AUTH_ONBOARDING_REQUIRED",
        "user-blocks-list-onboarding-required",
        BLOCKS_LIST_403);

    performUnblockError(
        delete("/api/v1/users/me/blocks/{userId}", "abc")
            .header(HttpHeaders.AUTHORIZATION, token(TENANT_ID)),
        status().isBadRequest(),
        "MALFORMED_REQUEST",
        "user-blocks-unblock-malformed-request",
        UNBLOCK_400);
    performUnblockError(
        delete("/api/v1/users/me/blocks/{userId}", LANDLORD_ID),
        status().isUnauthorized(),
        "UNAUTHENTICATED",
        "user-blocks-unblock-unauthenticated",
        UNBLOCK_401);
    performUnblockError(
        delete("/api/v1/users/me/blocks/{userId}", LANDLORD_ID)
            .header(HttpHeaders.AUTHORIZATION, expiredToken),
        status().isUnauthorized(),
        "TOKEN_EXPIRED",
        "user-blocks-unblock-token-expired",
        UNBLOCK_401);
    performUnblockError(
        delete("/api/v1/users/me/blocks/{userId}", LANDLORD_ID)
            .header(HttpHeaders.AUTHORIZATION, onboardingToken),
        status().isForbidden(),
        "AUTH_ONBOARDING_REQUIRED",
        "user-blocks-unblock-onboarding-required",
        UNBLOCK_403);
  }

  /** 목록은 path 변수가 없다 — 쿼리 파라미터는 생성기가 모델 전체를 합치므로 성공 스니펫의 선언이 그대로 남는다. */
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
                identifier,
                ApiDocsTags.USERS,
                BLOCKS_LIST_SUMMARY,
                BLOCKS_LIST_DESCRIPTION,
                errorCodes));
  }

  private void performUnblockError(
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
                identifier,
                ApiDocsTags.USERS,
                UNBLOCK_SUMMARY,
                UNBLOCK_DESCRIPTION,
                unblockPathParameters(),
                errorCodes));
  }
}
