package com.kohere.booking;

import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.document;
import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.resourceDetails;
import static com.kohere.docs.BookingDocsFields.BLOCK_400;
import static com.kohere.docs.BookingDocsFields.BLOCK_401;
import static com.kohere.docs.BookingDocsFields.BLOCK_403;
import static com.kohere.docs.BookingDocsFields.BLOCK_404;
import static com.kohere.docs.BookingDocsFields.BLOCK_DESCRIPTION;
import static com.kohere.docs.BookingDocsFields.BLOCK_SUMMARY;
import static com.kohere.docs.BookingDocsFields.CREATE_403;
import static com.kohere.docs.BookingDocsFields.CREATE_DESCRIPTION;
import static com.kohere.docs.BookingDocsFields.CREATE_SUMMARY;
import static com.kohere.docs.BookingDocsFields.DELETE_400;
import static com.kohere.docs.BookingDocsFields.DELETE_401;
import static com.kohere.docs.BookingDocsFields.DELETE_403;
import static com.kohere.docs.BookingDocsFields.DELETE_404;
import static com.kohere.docs.BookingDocsFields.DELETE_DESCRIPTION;
import static com.kohere.docs.BookingDocsFields.DELETE_SUMMARY;
import static com.kohere.docs.BookingDocsFields.REPORT_400;
import static com.kohere.docs.BookingDocsFields.REPORT_401;
import static com.kohere.docs.BookingDocsFields.REPORT_403;
import static com.kohere.docs.BookingDocsFields.REPORT_404;
import static com.kohere.docs.BookingDocsFields.REPORT_DESCRIPTION;
import static com.kohere.docs.BookingDocsFields.REPORT_REASONS_401;
import static com.kohere.docs.BookingDocsFields.REPORT_REASONS_403;
import static com.kohere.docs.BookingDocsFields.REPORT_REASONS_DESCRIPTION;
import static com.kohere.docs.BookingDocsFields.REPORT_REASONS_SUMMARY;
import static com.kohere.docs.BookingDocsFields.REPORT_REASON_CODES;
import static com.kohere.docs.BookingDocsFields.REPORT_SUMMARY;
import static com.kohere.docs.BookingDocsFields.blockPathParameters;
import static com.kohere.docs.BookingDocsFields.createPathParameters;
import static com.kohere.docs.BookingDocsFields.deletePathParameters;
import static com.kohere.docs.BookingDocsFields.reportPathParameters;
import static com.kohere.docs.BookingDocsFields.reportReasonsResponseFields;
import static com.kohere.docs.BookingDocsFields.reportRequestFields;
import static com.kohere.docs.BookingDocsFields.reportResponseFields;
import static com.kohere.docs.DocsTokens.bearer;
import static com.kohere.docs.DocsTokens.expiredAccessToken;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.delete;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.post;
import static org.springframework.restdocs.payload.PayloadDocumentation.requestFields;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.restdocs.request.RequestDocumentation.pathParameters;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
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
 * 예약 내역 관리(삭제·차단·신고·신고 사유) API 문서화 + 통합 테스트. docs/api/specs/04-booking-inquiry-chat.md §4~§7.
 *
 * <p>실제 MySQL(Testcontainers, JPA)에 저장하고 교차 모듈 협력({@code listing :: api}·{@code user :: api}의
 * {@code getUserType}·{@code getLanguage} 등)은 {@link MockitoBean}으로 대체한다. 차단(user_blocks)·삭제는 실제
 * 리포지토리에 기록되므로 테스트 격리를 위해 {@link Transactional}로 각 메서드 종료 시 롤백한다.
 *
 * <p>오퍼레이션 문구·필드 기술자는 §4~§7까지 전부 {@link BookingDocsFields}(Bookings 태그 한 벌)에 두고 여기서는 static import로
 * 참조한다 — 이 파일에는 테스트 흐름만 남긴다(#151). 특히 차단 관계 403({@code booking-create-blocked})은 예약 <b>생성</b>
 * 오퍼레이션({@code POST /api/v1/listings/{listingId}/bookings})에 병합되므로 문구·path 파라미터·에러 코드 배열을 생성 오퍼레이션의
 * 것과 공유해야 한다 — 같은 {@code (path, method)}의 summary/description은 첫 non-blank 하나만 채택되기 때문이다.
 */
@SpringBootTest
@ExtendWith(RestDocumentationExtension.class)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class BookingManagementDocsTest {

  private static final long TENANT_ID = 1L;
  private static final long LANDLORD_ID = 42L;
  private static final long OUTSIDER_ID = 99L;
  private static final String LISTING_ID = "6858e2000000000000000001";
  private static final String ROOM_OFFER_ID = "6858e2000000000000000abc";
  private static final Pattern BOOKING_ID = Pattern.compile("\"bookingId\"\\s*:\\s*(\\d+)");

  @Autowired private WebApplicationContext context;
  @Autowired private JwtTokenService jwtTokenService;
  @Autowired private JwtProperties jwtProperties;

  @MockitoBean private com.kohere.user.api.UserAccountService userAccountService;
  @MockitoBean private BookingListingQueryService listingQueryService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp(RestDocumentationContextProvider restDocumentation) {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .apply(springSecurity())
            .apply(documentationConfiguration(restDocumentation))
            .build();
    // 관리 API 테스트가 준비용 예약을 만들 때도 신청 카드 이벤트에 필요한 신청자 사본을 제공한다.
    given(userAccountService.getApplicantProfile(TENANT_ID)).willReturn(applicantView());
  }

  private ApplicantProfileView applicantView() {
    return new ApplicantProfileView(
        TENANT_ID, "Gil dong Hong", "MALE", "MN", "Mongolia", "kohere@gmail.com");
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

  /** 세입자로 예약 1건 생성하고 bookingId를 반환한다(차단 없는 상태). */
  private long createBooking() throws Exception {
    given(userAccountService.getUserType(TENANT_ID)).willReturn("TENANT");
    given(listingQueryService.findPublishedRoomOffer(LISTING_ID, ROOM_OFFER_ID))
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
    return Long.parseLong(m.group(1));
  }

  // ── §4 예약 내역 삭제 ─────────────────────────────────────────
  @Test
  void deleteBooking_success() throws Exception {
    long bookingId = createBooking();

    mockMvc
        .perform(
            delete("/api/v1/bookings/{bookingId}", bookingId)
                .header(HttpHeaders.AUTHORIZATION, token(TENANT_ID)))
        .andExpect(status().isNoContent())
        .andDo(
            document(
                "booking-delete",
                resourceDetails()
                    .tag(ApiDocsTags.BOOKINGS)
                    .summary(DELETE_SUMMARY)
                    .description(DELETE_DESCRIPTION),
                pathParameters(deletePathParameters())));
  }

  @Test
  void deleteBooking_idempotent() throws Exception {
    long bookingId = createBooking();
    for (int i = 0; i < 2; i++) {
      mockMvc
          .perform(
              delete("/api/v1/bookings/{bookingId}", bookingId)
                  .header(HttpHeaders.AUTHORIZATION, token(TENANT_ID)))
          .andExpect(status().isNoContent());
    }
  }

  @Test
  void deleteBooking_notParticipant_notFound() throws Exception {
    long bookingId = createBooking();

    perform(
        delete("/api/v1/bookings/{bookingId}", bookingId)
            .header(HttpHeaders.AUTHORIZATION, token(OUTSIDER_ID)),
        status().isNotFound(),
        "BOOKING_NOT_FOUND",
        "booking-delete-not-found",
        DELETE_SUMMARY,
        DELETE_DESCRIPTION,
        deletePathParameters(),
        DELETE_404);
  }

  @Test
  void deletedBooking_excludedFromList() throws Exception {
    long bookingId = createBooking();
    mockMvc
        .perform(
            delete("/api/v1/bookings/{bookingId}", bookingId)
                .header(HttpHeaders.AUTHORIZATION, token(TENANT_ID)))
        .andExpect(status().isNoContent());
    given(userAccountService.getUserType(TENANT_ID)).willReturn("TENANT");
    given(listingQueryService.findPublishedRoomOffer(anyString(), anyString()))
        .willReturn(Optional.of(offerView()));

    mockMvc
        .perform(get("/api/v1/bookings").header(HttpHeaders.AUTHORIZATION, token(TENANT_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[?(@.bookingId == " + bookingId + ")]").isEmpty());
  }

  // ── §5 예약 상대 차단 ─────────────────────────────────────────
  @Test
  void blockBooking_success() throws Exception {
    long bookingId = createBooking();

    mockMvc
        .perform(
            post("/api/v1/bookings/{bookingId}/block", bookingId)
                .header(HttpHeaders.AUTHORIZATION, token(TENANT_ID)))
        .andExpect(status().isNoContent())
        .andDo(
            document(
                "booking-block",
                resourceDetails()
                    .tag(ApiDocsTags.BOOKINGS)
                    .summary(BLOCK_SUMMARY)
                    .description(BLOCK_DESCRIPTION),
                pathParameters(blockPathParameters())));
  }

  @Test
  void blockBooking_idempotent() throws Exception {
    long bookingId = createBooking();
    for (int i = 0; i < 2; i++) {
      mockMvc
          .perform(
              post("/api/v1/bookings/{bookingId}/block", bookingId)
                  .header(HttpHeaders.AUTHORIZATION, token(TENANT_ID)))
          .andExpect(status().isNoContent());
    }
  }

  @Test
  void createBooking_blockedCounterpart_forbidden() throws Exception {
    long bookingId = createBooking();
    mockMvc
        .perform(
            post("/api/v1/bookings/{bookingId}/block", bookingId)
                .header(HttpHeaders.AUTHORIZATION, token(TENANT_ID)))
        .andExpect(status().isNoContent());

    // 차단 후 같은 상대(매물 소유자) 매물에 신규 신청 → 403(양방향 가드, 블랙홀 예약 방지)
    given(userAccountService.getUserType(TENANT_ID)).willReturn("TENANT");
    given(listingQueryService.findPublishedRoomOffer(LISTING_ID, ROOM_OFFER_ID))
        .willReturn(Optional.of(offerView()));
    // 이 스니펫만은 예약 "생성" 오퍼레이션에 병합된다 — 문구·path 파라미터·코드 배열을 공유 상수에서 가져온다.
    perform(
        post("/api/v1/listings/{listingId}/bookings", LISTING_ID)
            .header(HttpHeaders.AUTHORIZATION, token(TENANT_ID))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"roomOfferId\":\""
                    + ROOM_OFFER_ID
                    + "\",\"moveInDate\":\"2030-01-01\",\"contractPeriod\":6}"),
        status().isForbidden(),
        "FORBIDDEN",
        "booking-create-blocked",
        CREATE_SUMMARY,
        CREATE_DESCRIPTION,
        createPathParameters(),
        CREATE_403);
  }

  // ── §6 예약 신고 ─────────────────────────────────────────────
  @Test
  void reportBooking_success() throws Exception {
    long bookingId = createBooking();

    mockMvc
        .perform(
            post("/api/v1/bookings/{bookingId}/report", bookingId)
                .header(HttpHeaders.AUTHORIZATION, token(TENANT_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"ABUSE\",\"detail\":\"욕설을 했습니다\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.bookingId").value(bookingId))
        .andExpect(jsonPath("$.data.reason").value("ABUSE"))
        // 신고자 식별자·신고 상세 원문은 응답에 노출하지 않는다(프라이버시).
        .andExpect(jsonPath("$.data.reporterId").doesNotExist())
        .andExpect(jsonPath("$.data.detail").doesNotExist())
        .andDo(
            document(
                "booking-report",
                resourceDetails()
                    .tag(ApiDocsTags.BOOKINGS)
                    .summary(REPORT_SUMMARY)
                    .description(REPORT_DESCRIPTION),
                pathParameters(reportPathParameters()),
                requestFields(reportRequestFields()),
                responseFields(reportResponseFields())));
  }

  @Test
  void reportBooking_withoutReason_created() throws Exception {
    long bookingId = createBooking();

    mockMvc
        .perform(
            post("/api/v1/bookings/{bookingId}/report", bookingId)
                .header(HttpHeaders.AUTHORIZATION, token(TENANT_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isCreated())
        // 사유 없이 신고하면 `reason`은 키가 사라지는 것이 아니라 **null**이다(NON_NULL 미적용 DTO).
        .andExpect(jsonPath("$.data.reason").isEmpty())
        .andExpect(jsonPath("$.data.reportId").exists())
        // 신고자 식별자·신고 상세 원문은 여기서도 노출하지 않는다(booking-report와 동일 계약).
        .andExpect(jsonPath("$.data.reporterId").doesNotExist())
        .andExpect(jsonPath("$.data.detail").doesNotExist())
        .andDo(
            document(
                // 같은 (path, method, 201)이라 스키마는 booking-report와 병합된다 — 필드 헬퍼를 그대로 재사용해야
                // 하고, 이 스니펫은 `reason: null` 실물 예시를 examples 드롭다운에 추가하는 역할만 한다.
                "booking-report-no-reason",
                resourceDetails()
                    .tag(ApiDocsTags.BOOKINGS)
                    .summary(REPORT_SUMMARY)
                    .description(REPORT_DESCRIPTION),
                pathParameters(reportPathParameters()),
                requestFields(reportRequestFields()),
                responseFields(reportResponseFields())));
  }

  @Test
  void reportBooking_multipleAllowed() throws Exception {
    long bookingId = createBooking();
    // 동일 신고자가 동일 예약을 여러 번 신고할 수 있다(다건 허용 · 도배 방지는 후속 레이트리밋).
    for (String reason : new String[] {"SPAM", "ABUSE"}) {
      mockMvc
          .perform(
              post("/api/v1/bookings/{bookingId}/report", bookingId)
                  .header(HttpHeaders.AUTHORIZATION, token(TENANT_ID))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"reason\":\"" + reason + "\"}"))
          .andExpect(status().isCreated());
    }
  }

  @Test
  void reportBooking_notParticipant_notFound() throws Exception {
    long bookingId = createBooking();

    perform(
        post("/api/v1/bookings/{bookingId}/report", bookingId)
            .header(HttpHeaders.AUTHORIZATION, token(OUTSIDER_ID))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"SPAM\"}"),
        status().isNotFound(),
        "BOOKING_NOT_FOUND",
        "booking-report-not-found",
        REPORT_SUMMARY,
        REPORT_DESCRIPTION,
        reportPathParameters(),
        REPORT_404);
  }

  // ── §7 예약 신고 사유 목록(서버 번역) ─────────────────────────
  @Test
  void getReportReasons_translatedByUserLanguage() throws Exception {
    given(userAccountService.getLanguage(TENANT_ID)).willReturn("ko");

    mockMvc
        .perform(
            get("/api/v1/bookings/report-reasons")
                .header(HttpHeaders.AUTHORIZATION, token(TENANT_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.reasons[0].code").value("SPAM"))
        .andExpect(jsonPath("$.data.reasons[0].label").value("스팸/광고"))
        .andDo(
            document(
                "booking-report-reasons",
                resourceDetails()
                    .tag(ApiDocsTags.BOOKINGS)
                    .summary(REPORT_REASONS_SUMMARY)
                    .description(REPORT_REASONS_DESCRIPTION),
                responseFields(reportReasonsResponseFields())));
  }

  /**
   * 같은 오퍼레이션의 `en` 라벨 예시. 같은 {@code (path, method, 200)}이라 스키마는 {@code booking-report-reasons}와
   * 병합되고(필드 헬퍼 동일) examples 드롭다운에만 항목이 하나 더 생긴다 — 라벨이 표시 언어로 번역된다는 계약을 값으로 보여준다. 라벨 정본은 {@code
   * V17__create_booking_report_reasons.sql} 시드다.
   */
  @Test
  void getReportReasons_englishLabels() throws Exception {
    given(userAccountService.getLanguage(TENANT_ID)).willReturn("en");

    mockMvc
        .perform(
            get("/api/v1/bookings/report-reasons")
                .header(HttpHeaders.AUTHORIZATION, token(TENANT_ID)))
        .andExpect(status().isOk())
        // code·항목 수·순서는 언어와 무관하게 동일하고 label만 바뀐다.
        .andExpect(jsonPath("$.data.reasons.length()").value(REPORT_REASON_CODES.size()))
        .andExpect(jsonPath("$.data.reasons[0].code").value("SPAM"))
        .andExpect(jsonPath("$.data.reasons[0].label").value("Spam/Advertising"))
        .andExpect(jsonPath("$.data.reasons[5].code").value("ETC"))
        .andExpect(jsonPath("$.data.reasons[5].label").value("Other"))
        .andDo(
            document(
                "booking-report-reasons-en",
                resourceDetails()
                    .tag(ApiDocsTags.BOOKINGS)
                    .summary(REPORT_REASONS_SUMMARY)
                    .description(REPORT_REASONS_DESCRIPTION),
                responseFields(reportReasonsResponseFields())));
  }

  /**
   * §4~§7의 인증·입력 실패 스니펫. 대부분 보안 필터나 MVC 바인딩에서 끝나 예약 상태가 필요 없고, 실제 예약이 있어야 하는 것은 신고 {@code
   * INVALID_INPUT} 하나뿐이다({@code reportBooking}이 사유 검증보다 예약 조회를 먼저 한다).
   *
   * <p>본문은 <b>그 에러에 도달하는 데 필요할 때만</b> 싣는다(ADR-0017) — 필터가 거르는 401·403과, 본문 부재가 곧 트리거인 {@code
   * MALFORMED_REQUEST}에는 {@code content}를 넣지 않는다. {@code contentType}은 지우면 415가 되므로 남긴다.
   */
  @Test
  void managementErrorSnippets() throws Exception {
    String onboardingToken = bearer(jwtTokenService.issueOnboardingToken(TENANT_ID));
    String expiredToken = bearer(expiredAccessToken(jwtProperties));

    // ===== §4 DELETE /api/v1/bookings/{bookingId} =====
    performDeleteError(
        delete("/api/v1/bookings/{bookingId}", "abc")
            .header(HttpHeaders.AUTHORIZATION, token(TENANT_ID)),
        status().isBadRequest(),
        "MALFORMED_REQUEST",
        "booking-delete-malformed-request",
        DELETE_400);
    performDeleteError(
        delete("/api/v1/bookings/{bookingId}", 1L),
        status().isUnauthorized(),
        "UNAUTHENTICATED",
        "booking-delete-unauthenticated",
        DELETE_401);
    performDeleteError(
        delete("/api/v1/bookings/{bookingId}", 1L).header(HttpHeaders.AUTHORIZATION, expiredToken),
        status().isUnauthorized(),
        "TOKEN_EXPIRED",
        "booking-delete-token-expired",
        DELETE_401);
    performDeleteError(
        delete("/api/v1/bookings/{bookingId}", 1L)
            .header(HttpHeaders.AUTHORIZATION, onboardingToken),
        status().isForbidden(),
        "AUTH_ONBOARDING_REQUIRED",
        "booking-delete-onboarding-required",
        DELETE_403);

    // ===== §5 POST /api/v1/bookings/{bookingId}/block =====
    performBlockError(
        post("/api/v1/bookings/{bookingId}/block", "abc")
            .header(HttpHeaders.AUTHORIZATION, token(TENANT_ID)),
        status().isBadRequest(),
        "MALFORMED_REQUEST",
        "booking-block-malformed-request",
        BLOCK_400);
    performBlockError(
        post("/api/v1/bookings/{bookingId}/block", 1L),
        status().isUnauthorized(),
        "UNAUTHENTICATED",
        "booking-block-unauthenticated",
        BLOCK_401);
    performBlockError(
        post("/api/v1/bookings/{bookingId}/block", 1L)
            .header(HttpHeaders.AUTHORIZATION, expiredToken),
        status().isUnauthorized(),
        "TOKEN_EXPIRED",
        "booking-block-token-expired",
        BLOCK_401);
    performBlockError(
        post("/api/v1/bookings/{bookingId}/block", 1L)
            .header(HttpHeaders.AUTHORIZATION, onboardingToken),
        status().isForbidden(),
        "AUTH_ONBOARDING_REQUIRED",
        "booking-block-onboarding-required",
        BLOCK_403);
    performBlockError(
        post("/api/v1/bookings/{bookingId}/block", 999_999L)
            .header(HttpHeaders.AUTHORIZATION, token(TENANT_ID)),
        status().isNotFound(),
        "BOOKING_NOT_FOUND",
        "booking-block-not-found",
        BLOCK_404);

    // ===== §6 POST /api/v1/bookings/{bookingId}/report =====
    long bookingId = createBooking();
    performReportError(
        post("/api/v1/bookings/{bookingId}/report", bookingId)
            .header(HttpHeaders.AUTHORIZATION, token(TENANT_ID))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"NOT_A_REASON\"}"),
        status().isBadRequest(),
        "INVALID_INPUT",
        "booking-report-invalid-input",
        REPORT_400);
    performReportError(
        post("/api/v1/bookings/{bookingId}/report", bookingId)
            .header(HttpHeaders.AUTHORIZATION, token(TENANT_ID))
            .contentType(MediaType.APPLICATION_JSON),
        status().isBadRequest(),
        "MALFORMED_REQUEST",
        "booking-report-malformed-request",
        REPORT_400);
    performReportError(
        post("/api/v1/bookings/{bookingId}/report", bookingId)
            .contentType(MediaType.APPLICATION_JSON),
        status().isUnauthorized(),
        "UNAUTHENTICATED",
        "booking-report-unauthenticated",
        REPORT_401);
    performReportError(
        post("/api/v1/bookings/{bookingId}/report", bookingId)
            .header(HttpHeaders.AUTHORIZATION, expiredToken)
            .contentType(MediaType.APPLICATION_JSON),
        status().isUnauthorized(),
        "TOKEN_EXPIRED",
        "booking-report-token-expired",
        REPORT_401);
    performReportError(
        post("/api/v1/bookings/{bookingId}/report", bookingId)
            .header(HttpHeaders.AUTHORIZATION, onboardingToken)
            .contentType(MediaType.APPLICATION_JSON),
        status().isForbidden(),
        "AUTH_ONBOARDING_REQUIRED",
        "booking-report-onboarding-required",
        REPORT_403);

    // ===== §7 GET /api/v1/bookings/report-reasons =====
    performReportReasonsError(
        get("/api/v1/bookings/report-reasons"),
        status().isUnauthorized(),
        "UNAUTHENTICATED",
        "booking-report-reasons-unauthenticated",
        REPORT_REASONS_401);
    performReportReasonsError(
        get("/api/v1/bookings/report-reasons").header(HttpHeaders.AUTHORIZATION, expiredToken),
        status().isUnauthorized(),
        "TOKEN_EXPIRED",
        "booking-report-reasons-token-expired",
        REPORT_REASONS_401);
    performReportReasonsError(
        get("/api/v1/bookings/report-reasons").header(HttpHeaders.AUTHORIZATION, onboardingToken),
        status().isForbidden(),
        "AUTH_ONBOARDING_REQUIRED",
        "booking-report-reasons-onboarding-required",
        REPORT_REASONS_403);
  }

  private void performDeleteError(
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
        DELETE_SUMMARY,
        DELETE_DESCRIPTION,
        deletePathParameters(),
        errorCodes);
  }

  private void performBlockError(
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
        BLOCK_SUMMARY,
        BLOCK_DESCRIPTION,
        blockPathParameters(),
        errorCodes);
  }

  private void performReportError(
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
        REPORT_SUMMARY,
        REPORT_DESCRIPTION,
        reportPathParameters(),
        errorCodes);
  }

  /** 신고 사유 목록은 path 변수가 없다 — {@code pathParameters} 없는 오버로드를 쓴다. */
  private void performReportReasonsError(
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
                ApiDocsTags.BOOKINGS,
                REPORT_REASONS_SUMMARY,
                REPORT_REASONS_DESCRIPTION,
                errorCodes));
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
