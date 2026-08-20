package com.kohere.chat;

import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.document;
import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.resourceDetails;
import static com.kohere.docs.ChatDocsFields.INQUIRY_401;
import static com.kohere.docs.ChatDocsFields.INQUIRY_403;
import static com.kohere.docs.ChatDocsFields.INQUIRY_404;
import static com.kohere.docs.ChatDocsFields.INQUIRY_422;
import static com.kohere.docs.ChatDocsFields.INQUIRY_DESCRIPTION;
import static com.kohere.docs.ChatDocsFields.INQUIRY_SUMMARY;
import static com.kohere.docs.ChatDocsFields.inquiryPathParameters;
import static com.kohere.docs.ChatDocsFields.inquiryResponseFields;
import static com.kohere.docs.DocsTokens.bearer;
import static org.mockito.BDDMockito.given;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.post;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.restdocs.request.RequestDocumentation.pathParameters;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kohere.TestcontainersConfiguration;
import com.kohere.common.security.JwtTokenService;
import com.kohere.docs.ApiDocsErrors;
import com.kohere.docs.ApiDocsTags;
import com.kohere.listing.api.ChatListingQueryService;
import com.kohere.listing.api.ChatListingView;
import com.kohere.user.api.UserAccountService;
import com.kohere.user.api.UserBlockService;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
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
 * 문의 채팅방 API를 실제 Spring Security·서비스·MySQL과 연결해 검증하고 Swagger/OpenAPI 스니펫을 생성한다.
 *
 * <p>listing과 user는 각 모듈의 공개 계약만 mock으로 대체한다. 방과 참여자 저장, 신규 201·기존 200 구분은 실제 구현을 사용하므로 문서 예시가 런타임
 * 동작과 따로 움직이지 않는다.
 */
@SpringBootTest
@ExtendWith(RestDocumentationExtension.class)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class ChatDocsTest {

  private static final long TENANT_ID = 7L;
  private static final long LANDLORD_ID = 42L;
  private static final String LISTING_ID = "6858e2000000000000000001";
  private static final Pattern CHAT_ROOM_ID_PATTERN =
      Pattern.compile("\\\"chatRoomId\\\"\\s*:\\s*(\\d+)");

  @Autowired private WebApplicationContext context;
  @Autowired private JwtTokenService jwtTokenService;

  @MockitoBean private ChatListingQueryService listingQueryService;
  @MockitoBean private UserAccountService userAccountService;
  @MockitoBean private UserBlockService userBlockService;

  private MockMvc mockMvc;

  /** 매 테스트가 실제 Security filter와 REST Docs 설정을 통과하도록 MockMvc를 구성한다. */
  @BeforeEach
  void setUp(RestDocumentationContextProvider restDocumentation) {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .apply(springSecurity())
            .apply(documentationConfiguration(restDocumentation))
            .build();
    given(userAccountService.getUserType(TENANT_ID)).willReturn("TENANT");
    given(listingQueryService.findPublishedListing(LISTING_ID))
        .willReturn(Optional.of(listingView(LANDLORD_ID)));
    given(userBlockService.isBlockedBetween(TENANT_ID, LANDLORD_ID)).willReturn(false);
  }

  /** 새 방은 201과 created=true를 반환하며 Swagger 성공 예시의 정본이 된다. */
  @Test
  @DisplayName("문의 채팅방 신규 생성 문서화")
  void createInquiryCreated() throws Exception {
    mockMvc
        .perform(inquiryRequest().header(HttpHeaders.AUTHORIZATION, accessToken()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.chatRoomId").isNumber())
        .andExpect(jsonPath("$.data.created").value(true))
        .andDo(
            document(
                "chat-inquiry-create",
                resourceDetails()
                    .tag(ApiDocsTags.CHATS)
                    .summary(INQUIRY_SUMMARY)
                    .description(INQUIRY_DESCRIPTION),
                pathParameters(inquiryPathParameters()),
                responseFields(inquiryResponseFields())));
  }

  /** 같은 키의 두 번째 요청은 같은 roomId와 200·created=false를 반환한다. */
  @Test
  @DisplayName("문의 채팅방 기존 방 반환 문서화")
  void createInquiryExisting() throws Exception {
    String token = accessToken();
    long roomId =
        mockMvc
            .perform(inquiryRequest().header(HttpHeaders.AUTHORIZATION, token))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString()
            .lines()
            .map(String::trim)
            .filter(line -> line.contains("chatRoomId"))
            .findFirst()
            .map(ChatDocsTest::roomIdFromJsonLine)
            .orElseThrow();

    mockMvc
        .perform(inquiryRequest().header(HttpHeaders.AUTHORIZATION, token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.chatRoomId").value(roomId))
        .andExpect(jsonPath("$.data.created").value(false))
        .andDo(
            document(
                "chat-inquiry-existing",
                resourceDetails()
                    .tag(ApiDocsTags.CHATS)
                    .summary(INQUIRY_SUMMARY)
                    .description(INQUIRY_DESCRIPTION),
                pathParameters(inquiryPathParameters()),
                responseFields(inquiryResponseFields())));
  }

  /** 세입자 전용 규칙의 공통 403 응답을 문서화한다. */
  @Test
  @DisplayName("비세입자 문의 거부 문서화")
  void createInquiryForbiddenForNonTenant() throws Exception {
    given(userAccountService.getUserType(TENANT_ID)).willReturn("LANDLORD");

    performError(
        inquiryRequest().header(HttpHeaders.AUTHORIZATION, accessToken()),
        status().isForbidden(),
        "FORBIDDEN",
        "chat-inquiry-forbidden",
        INQUIRY_403);
  }

  /** 차단 방향을 노출하지 않는 CHAT_UNAVAILABLE 403 응답을 문서화한다. */
  @Test
  @DisplayName("차단 관계 문의 거부 문서화")
  void createInquiryBlocked() throws Exception {
    given(userBlockService.isBlockedBetween(TENANT_ID, LANDLORD_ID)).willReturn(true);

    performError(
        inquiryRequest().header(HttpHeaders.AUTHORIZATION, accessToken()),
        status().isForbidden(),
        "CHAT_UNAVAILABLE",
        "chat-inquiry-blocked",
        INQUIRY_403);
  }

  /** 매물 부재·비공개를 같은 404로 처리하는 계약을 문서화한다. */
  @Test
  @DisplayName("문의 가능 매물 부재 문서화")
  void createInquiryListingNotFound() throws Exception {
    given(listingQueryService.findPublishedListing(LISTING_ID)).willReturn(Optional.empty());

    performError(
        inquiryRequest().header(HttpHeaders.AUTHORIZATION, accessToken()),
        status().isNotFound(),
        "LISTING_NOT_FOUND",
        "chat-inquiry-listing-not-found",
        INQUIRY_404);
  }

  /** 매물 정본에서 소유자가 본인으로 확인된 경우의 422를 문서화한다. */
  @Test
  @DisplayName("본인 매물 문의 거부 문서화")
  void createInquirySelfListing() throws Exception {
    given(listingQueryService.findPublishedListing(LISTING_ID))
        .willReturn(Optional.of(listingView(TENANT_ID)));

    performError(
        inquiryRequest().header(HttpHeaders.AUTHORIZATION, accessToken()),
        status().isUnprocessableEntity(),
        "CHAT_SELF_INQUIRY_NOT_ALLOWED",
        "chat-inquiry-self",
        INQUIRY_422);
  }

  /** 토큰 없는 요청이 서비스까지 도달하지 않는 공통 401을 문서화한다. */
  @Test
  @DisplayName("문의 인증 누락 문서화")
  void createInquiryUnauthenticated() throws Exception {
    performError(
        inquiryRequest(),
        status().isUnauthorized(),
        "UNAUTHENTICATED",
        "chat-inquiry-unauthenticated",
        INQUIRY_401);
  }

  /** 현재 사용자의 access token을 Bearer 헤더 값으로 만든다. */
  private String accessToken() {
    return bearer(jwtTokenService.issueAccessToken(TENANT_ID));
  }

  /** 모든 문의 테스트가 같은 경로 변수 선언을 사용하도록 요청 빌더를 한곳에 둔다. */
  private static MockHttpServletRequestBuilder inquiryRequest() {
    return post("/api/v1/listings/{listingId}/inquiries", LISTING_ID);
  }

  /** 오류 status가 달라도 같은 오퍼레이션 설명과 path 계약을 사용해 Swagger 병합 순서 영향을 없앤다. */
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
            ApiDocsErrors.errorSnippet(
                identifier,
                ApiDocsTags.CHATS,
                INQUIRY_SUMMARY,
                INQUIRY_DESCRIPTION,
                inquiryPathParameters(),
                errorCodes));
  }

  /** ChatListingView는 일반 방에서 사용하는 제목·주소만 담고 신청 카드 이미지는 별도 payload가 담당한다. */
  private static ChatListingView listingView(long landlordId) {
    return new ChatListingView(
        LISTING_ID, landlordId, "Hongdae Studio share", "Seogyo-dong, Mapo-gu");
  }

  /** JSON의 공백·줄바꿈 형식에 의존하지 않고 숫자 roomId를 꺼내 두 번째 요청의 동일성을 검증한다. */
  private static long roomIdFromJsonLine(String json) {
    Matcher matcher = CHAT_ROOM_ID_PATTERN.matcher(json);
    if (!matcher.find()) {
      throw new IllegalArgumentException("응답에 chatRoomId가 없습니다.");
    }
    return Long.parseLong(matcher.group(1));
  }
}
