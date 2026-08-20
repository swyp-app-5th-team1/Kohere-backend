package com.kohere.chat;

import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.document;
import static com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper.resourceDetails;
import static com.kohere.docs.ChatDocsFields.INQUIRY_401;
import static com.kohere.docs.ChatDocsFields.INQUIRY_403;
import static com.kohere.docs.ChatDocsFields.INQUIRY_404;
import static com.kohere.docs.ChatDocsFields.INQUIRY_422;
import static com.kohere.docs.ChatDocsFields.INQUIRY_DESCRIPTION;
import static com.kohere.docs.ChatDocsFields.INQUIRY_SUMMARY;
import static com.kohere.docs.ChatDocsFields.MESSAGE_HISTORY_400;
import static com.kohere.docs.ChatDocsFields.MESSAGE_HISTORY_401;
import static com.kohere.docs.ChatDocsFields.MESSAGE_HISTORY_403;
import static com.kohere.docs.ChatDocsFields.MESSAGE_HISTORY_404;
import static com.kohere.docs.ChatDocsFields.MESSAGE_HISTORY_DESCRIPTION;
import static com.kohere.docs.ChatDocsFields.MESSAGE_HISTORY_SUMMARY;
import static com.kohere.docs.ChatDocsFields.ROOM_DETAIL_400;
import static com.kohere.docs.ChatDocsFields.ROOM_DETAIL_401;
import static com.kohere.docs.ChatDocsFields.ROOM_DETAIL_403;
import static com.kohere.docs.ChatDocsFields.ROOM_DETAIL_404;
import static com.kohere.docs.ChatDocsFields.ROOM_DETAIL_DESCRIPTION;
import static com.kohere.docs.ChatDocsFields.ROOM_DETAIL_SUMMARY;
import static com.kohere.docs.ChatDocsFields.ROOM_LIST_400;
import static com.kohere.docs.ChatDocsFields.ROOM_LIST_401;
import static com.kohere.docs.ChatDocsFields.ROOM_LIST_403;
import static com.kohere.docs.ChatDocsFields.ROOM_LIST_DESCRIPTION;
import static com.kohere.docs.ChatDocsFields.ROOM_LIST_SUMMARY;
import static com.kohere.docs.ChatDocsFields.inquiryPathParameters;
import static com.kohere.docs.ChatDocsFields.inquiryResponseFields;
import static com.kohere.docs.ChatDocsFields.messageHistoryPathParameters;
import static com.kohere.docs.ChatDocsFields.messageHistoryQueryParameters;
import static com.kohere.docs.ChatDocsFields.messageHistoryResponseFields;
import static com.kohere.docs.ChatDocsFields.roomDetailPathParameters;
import static com.kohere.docs.ChatDocsFields.roomDetailResponseFields;
import static com.kohere.docs.ChatDocsFields.roomListQueryParameters;
import static com.kohere.docs.ChatDocsFields.roomListResponseFields;
import static com.kohere.docs.DocsTokens.bearer;
import static com.kohere.docs.DocsTokens.expiredAccessToken;
import static org.mockito.BDDMockito.given;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.post;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.restdocs.request.RequestDocumentation.pathParameters;
import static org.springframework.restdocs.request.RequestDocumentation.queryParameters;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kohere.TestcontainersConfiguration;
import com.kohere.chat.domain.ChatRoom;
import com.kohere.chat.domain.ChatRoomMember;
import com.kohere.chat.domain.ChatRoomMemberRepository;
import com.kohere.chat.domain.ChatRoomRepository;
import com.kohere.chat.domain.Message;
import com.kohere.chat.domain.MessageRepository;
import com.kohere.chat.domain.MessageType;
import com.kohere.common.security.JwtProperties;
import com.kohere.common.security.JwtTokenService;
import com.kohere.docs.ApiDocsErrors;
import com.kohere.docs.ApiDocsTags;
import com.kohere.listing.api.ChatListingQueryService;
import com.kohere.listing.api.ChatListingView;
import com.kohere.user.api.UserAccountService;
import com.kohere.user.api.UserBlockService;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
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
  @Autowired private JwtProperties jwtProperties;
  @Autowired private JwtTokenService jwtTokenService;
  @Autowired private ChatRoomRepository chatRoomRepository;
  @Autowired private ChatRoomMemberRepository chatRoomMemberRepository;
  @Autowired private MessageRepository messageRepository;

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
    given(userAccountService.getUserName(LANDLORD_ID)).willReturn("Hongdae landlord");
  }

  /** 채팅방 목록의 실제 DB 조회·마지막 메시지 조립과 Swagger 응답 계약을 함께 검증한다. */
  @Test
  @DisplayName("내 채팅방 목록 조회 문서화")
  void listChatRooms() throws Exception {
    long roomId = createRoomThroughInquiry();
    ChatRoom room = chatRoomRepository.findById(roomId).orElseThrow();
    Instant sentAt = Instant.parse("2026-08-20T10:15:30.123456Z");
    Message lastMessage =
        messageRepository.save(
            Message.builder()
                .chatRoomId(roomId)
                .senderId(TENANT_ID)
                .type(MessageType.TEXT)
                .content("Is the room still available?")
                .clientMessageId(UUID.fromString("b6506eb7-bf2d-47c8-a8d2-5f75cdb6d849"))
                .sentAt(sentAt)
                .build());
    chatRoomRepository.save(roomWithLastMessage(room, lastMessage));

    mockMvc
        .perform(
            get("/api/v1/chat-rooms")
                .header(HttpHeaders.AUTHORIZATION, accessToken())
                .param("page", "0")
                .param("size", "20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[0].chatRoomId").value(roomId))
        .andExpect(jsonPath("$.data.content[0].counterpart.userId").value(LANDLORD_ID))
        .andExpect(jsonPath("$.data.content[0].counterpart.displayName").value("Hongdae landlord"))
        .andExpect(
            jsonPath("$.data.content[0].lastMessage.preview").value("Is the room still available?"))
        .andExpect(jsonPath("$.data.page.totalElements").value(1))
        .andDo(
            document(
                "chat-room-list",
                resourceDetails()
                    .tag(ApiDocsTags.CHATS)
                    .summary(ROOM_LIST_SUMMARY)
                    .description(ROOM_LIST_DESCRIPTION),
                queryParameters(roomListQueryParameters()),
                responseFields(roomListResponseFields())));
  }

  /** 잘못된 페이지 범위가 400으로 반환되는 계약도 같은 Swagger 오퍼레이션에 합친다. */
  @Test
  @DisplayName("내 채팅방 목록 잘못된 페이지 문서화")
  void listChatRoomsInvalidPage() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/chat-rooms")
                .header(HttpHeaders.AUTHORIZATION, accessToken())
                .param("page", "-1")
                .param("size", "20"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
        .andDo(
            ApiDocsErrors.errorSnippet(
                "chat-room-list-invalid-page",
                ApiDocsTags.CHATS,
                ROOM_LIST_SUMMARY,
                ROOM_LIST_DESCRIPTION,
                ROOM_LIST_400));
  }

  /** 숫자가 아닌 page는 컨트롤러 파라미터 변환 단계에서 400으로 거부됨을 Swagger에 보여 준다. */
  @Test
  @DisplayName("내 채팅방 목록 숫자가 아닌 페이지 문서화")
  void listChatRoomsMalformedPage() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/chat-rooms")
                .header(HttpHeaders.AUTHORIZATION, accessToken())
                .param("page", "first"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"))
        .andDo(
            ApiDocsErrors.errorSnippet(
                "chat-room-list-malformed-page",
                ApiDocsTags.CHATS,
                ROOM_LIST_SUMMARY,
                ROOM_LIST_DESCRIPTION,
                ROOM_LIST_400));
  }

  /** 목록 API도 access token 누락·만료·온보딩 미완료 응답을 다른 Chats API와 동일하게 문서화한다. */
  @Test
  @DisplayName("내 채팅방 목록 인증 오류 문서화")
  void listChatRoomsAuthenticationErrors() throws Exception {
    mockMvc
        .perform(get("/api/v1/chat-rooms"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
        .andDo(
            ApiDocsErrors.errorSnippet(
                "chat-room-list-unauthenticated",
                ApiDocsTags.CHATS,
                ROOM_LIST_SUMMARY,
                ROOM_LIST_DESCRIPTION,
                ROOM_LIST_401));

    mockMvc
        .perform(
            get("/api/v1/chat-rooms").header(HttpHeaders.AUTHORIZATION, expiredAccessTokenHeader()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("TOKEN_EXPIRED"))
        .andDo(
            ApiDocsErrors.errorSnippet(
                "chat-room-list-token-expired",
                ApiDocsTags.CHATS,
                ROOM_LIST_SUMMARY,
                ROOM_LIST_DESCRIPTION,
                ROOM_LIST_401));

    mockMvc
        .perform(
            get("/api/v1/chat-rooms")
                .header(
                    HttpHeaders.AUTHORIZATION,
                    bearer(jwtTokenService.issueOnboardingToken(TENANT_ID))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("AUTH_ONBOARDING_REQUIRED"))
        .andDo(
            ApiDocsErrors.errorSnippet(
                "chat-room-list-onboarding-required",
                ApiDocsTags.CHATS,
                ROOM_LIST_SUMMARY,
                ROOM_LIST_DESCRIPTION,
                ROOM_LIST_403));
  }

  /** 목록·딥링크에서 받은 roomId로 채팅방 기본 정보를 조회하는 성공 응답을 문서화한다. */
  @Test
  @DisplayName("채팅방 단건 조회 문서화")
  void getChatRoomDetail() throws Exception {
    long roomId = createRoomThroughInquiry();

    mockMvc
        .perform(
            get("/api/v1/chat-rooms/{roomId}", roomId)
                .header(HttpHeaders.AUTHORIZATION, accessToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.chatRoomId").value(roomId))
        .andExpect(jsonPath("$.data.myRole").value("TENANT"))
        .andExpect(jsonPath("$.data.listing.listingId").value(LISTING_ID))
        .andExpect(jsonPath("$.data.counterpart.userId").value(LANDLORD_ID))
        .andExpect(jsonPath("$.data.counterpart.displayName").value("Hongdae landlord"))
        .andExpect(jsonPath("$.data.blocked").value(false))
        .andDo(
            document(
                "chat-room-detail",
                resourceDetails()
                    .tag(ApiDocsTags.CHATS)
                    .summary(ROOM_DETAIL_SUMMARY)
                    .description(ROOM_DETAIL_DESCRIPTION),
                pathParameters(roomDetailPathParameters()),
                responseFields(roomDetailResponseFields())));
  }

  /** 존재하는 roomId여도 다른 사용자는 동일한 404를 받아 방 존재 여부를 알 수 없음을 문서화한다. */
  @Test
  @DisplayName("채팅방 단건 비참여자 404 문서화")
  void getChatRoomDetailAsOutsider() throws Exception {
    long roomId = createRoomThroughInquiry();
    String outsiderToken = bearer(jwtTokenService.issueAccessToken(999L));

    mockMvc
        .perform(
            get("/api/v1/chat-rooms/{roomId}", roomId)
                .header(HttpHeaders.AUTHORIZATION, outsiderToken))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("CHAT_ROOM_NOT_FOUND"))
        .andDo(
            ApiDocsErrors.errorSnippet(
                "chat-room-detail-not-found",
                ApiDocsTags.CHATS,
                ROOM_DETAIL_SUMMARY,
                ROOM_DETAIL_DESCRIPTION,
                roomDetailPathParameters(),
                ROOM_DETAIL_404));
  }

  /** roomId 자리에 숫자가 아닌 값을 넣었을 때의 400을 보여 줘 프론트가 ID 형식을 바로 확인할 수 있게 한다. */
  @Test
  @DisplayName("채팅방 단건 숫자가 아닌 roomId 문서화")
  void getChatRoomDetailWithMalformedRoomId() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/chat-rooms/{roomId}", "room-id")
                .header(HttpHeaders.AUTHORIZATION, accessToken()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"))
        .andDo(
            ApiDocsErrors.errorSnippet(
                "chat-room-detail-malformed-room-id",
                ApiDocsTags.CHATS,
                ROOM_DETAIL_SUMMARY,
                ROOM_DETAIL_DESCRIPTION,
                roomDetailPathParameters(),
                ROOM_DETAIL_400));
  }

  /** 토큰이 없는 단건 조회가 컨트롤러 전에 401로 차단되는 계약을 문서화한다. */
  @Test
  @DisplayName("채팅방 단건 인증 누락 문서화")
  void getChatRoomDetailUnauthenticated() throws Exception {
    mockMvc
        .perform(get("/api/v1/chat-rooms/{roomId}", 556L))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
        .andDo(
            ApiDocsErrors.errorSnippet(
                "chat-room-detail-unauthenticated",
                ApiDocsTags.CHATS,
                ROOM_DETAIL_SUMMARY,
                ROOM_DETAIL_DESCRIPTION,
                roomDetailPathParameters(),
                ROOM_DETAIL_401));
  }

  /** 만료된 access token은 재발급 후 다시 요청할 수 있도록 TOKEN_EXPIRED로 구분해 문서화한다. */
  @Test
  @DisplayName("채팅방 단건 만료 토큰 문서화")
  void getChatRoomDetailWithExpiredToken() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/chat-rooms/{roomId}", 556L)
                .header(HttpHeaders.AUTHORIZATION, expiredAccessTokenHeader()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("TOKEN_EXPIRED"))
        .andDo(
            ApiDocsErrors.errorSnippet(
                "chat-room-detail-token-expired",
                ApiDocsTags.CHATS,
                ROOM_DETAIL_SUMMARY,
                ROOM_DETAIL_DESCRIPTION,
                roomDetailPathParameters(),
                ROOM_DETAIL_401));
  }

  /** 온보딩 임시 토큰은 정식 채팅방에 접근할 수 없다는 403 계약을 문서화한다. */
  @Test
  @DisplayName("채팅방 단건 온보딩 사용자 거부 문서화")
  void getChatRoomDetailWithOnboardingToken() throws Exception {
    String onboardingToken = bearer(jwtTokenService.issueOnboardingToken(TENANT_ID));

    mockMvc
        .perform(
            get("/api/v1/chat-rooms/{roomId}", 556L)
                .header(HttpHeaders.AUTHORIZATION, onboardingToken))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("AUTH_ONBOARDING_REQUIRED"))
        .andDo(
            ApiDocsErrors.errorSnippet(
                "chat-room-detail-onboarding-required",
                ApiDocsTags.CHATS,
                ROOM_DETAIL_SUMMARY,
                ROOM_DETAIL_DESCRIPTION,
                roomDetailPathParameters(),
                ROOM_DETAIL_403));
  }

  /** 최근 메시지 조회의 실제 MySQL 결과와 Swagger 커서 응답 계약을 함께 검증한다. */
  @Test
  @DisplayName("채팅방 최근 메시지 조회 문서화")
  void getRecentMessages() throws Exception {
    long roomId = createRoomThroughInquiry();
    Message message =
        messageRepository.save(
            Message.builder()
                .chatRoomId(roomId)
                .senderId(LANDLORD_ID)
                .type(MessageType.TEXT)
                .content("The room is available from September 1.")
                .clientMessageId(UUID.fromString("0c3f0261-90bb-4ba5-a429-d669f7b92222"))
                .sentAt(Instant.parse("2026-08-20T10:20:30.123456Z"))
                .build());

    mockMvc
        .perform(
            get("/api/v1/chat-rooms/{roomId}/messages", roomId)
                .header(HttpHeaders.AUTHORIZATION, accessToken())
                .param("size", "30"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[0].messageId").value(message.getId()))
        .andExpect(jsonPath("$.data.content[0].senderId").value(LANDLORD_ID))
        .andExpect(jsonPath("$.data.content[0].mine").value(false))
        .andExpect(jsonPath("$.data.content[0].type").value("TEXT"))
        .andExpect(
            jsonPath("$.data.content[0].originalContent")
                .value("The room is available from September 1."))
        .andExpect(jsonPath("$.data.content[0].bookingCard").isEmpty())
        .andExpect(jsonPath("$.data.content[0].translation").isEmpty())
        .andExpect(jsonPath("$.data.nextCursor").isEmpty())
        .andExpect(jsonPath("$.data.hasNext").value(false))
        .andDo(
            document(
                "chat-message-history",
                resourceDetails()
                    .tag(ApiDocsTags.CHATS)
                    .summary(MESSAGE_HISTORY_SUMMARY)
                    .description(MESSAGE_HISTORY_DESCRIPTION),
                pathParameters(messageHistoryPathParameters()),
                queryParameters(messageHistoryQueryParameters()),
                responseFields(messageHistoryResponseFields())));
  }

  /** 채팅방 삭제 시점 이전의 메시지가 실제 HTTP 응답에서도 로그인 사용자에게 다시 노출되지 않는지 검증한다. */
  @Test
  @DisplayName("채팅방 메시지 개인 삭제 경계 적용")
  void getMessagesExcludesPersonallyDeletedHistory() throws Exception {
    long roomId = createRoomThroughInquiry();
    Message deletedMessage =
        messageRepository.save(
            Message.builder()
                .chatRoomId(roomId)
                .senderId(LANDLORD_ID)
                .type(MessageType.TEXT)
                .content("This message was in the deleted history.")
                .clientMessageId(UUID.fromString("a77330b6-b1b3-49c0-a49f-a9aa382b8988"))
                .sentAt(Instant.parse("2026-08-20T10:20:00Z"))
                .build());
    Message visibleMessage =
        messageRepository.save(
            Message.builder()
                .chatRoomId(roomId)
                .senderId(LANDLORD_ID)
                .type(MessageType.TEXT)
                .content("This message arrived after the deleted history.")
                .clientMessageId(UUID.fromString("99da02ba-a51a-4b4a-ad8e-ce207a8794b0"))
                .sentAt(Instant.parse("2026-08-20T10:21:00Z"))
                .build());

    ChatRoomMember tenantMember =
        chatRoomMemberRepository.findByChatRoomIdAndUserId(roomId, TENANT_ID).orElseThrow();
    chatRoomMemberRepository.save(
        tenantMember.toBuilder()
            .historyHiddenThroughMessageId(deletedMessage.getId())
            .updatedAt(Instant.parse("2026-08-20T10:20:30Z"))
            .build());

    mockMvc
        .perform(
            get("/api/v1/chat-rooms/{roomId}/messages", roomId)
                .header(HttpHeaders.AUTHORIZATION, accessToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content.length()").value(1))
        .andExpect(jsonPath("$.data.content[0].messageId").value(visibleMessage.getId()))
        .andExpect(
            jsonPath("$.data.content[0].originalContent")
                .value("This message arrived after the deleted history."));
  }

  /** 두 커서를 함께 보내 조회 방향이 모호한 요청은 400으로 거부하는 계약을 문서화한다. */
  @Test
  @DisplayName("채팅방 메시지 두 커서 동시 사용 거부 문서화")
  void getMessagesWithTwoCursors() throws Exception {
    long roomId = createRoomThroughInquiry();

    mockMvc
        .perform(
            get("/api/v1/chat-rooms/{roomId}/messages", roomId)
                .header(HttpHeaders.AUTHORIZATION, accessToken())
                .param("cursor", "100")
                .param("afterMessageId", "101"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
        .andDo(
            ApiDocsErrors.errorSnippet(
                "chat-message-history-invalid-cursors",
                ApiDocsTags.CHATS,
                MESSAGE_HISTORY_SUMMARY,
                MESSAGE_HISTORY_DESCRIPTION,
                messageHistoryPathParameters(),
                MESSAGE_HISTORY_400));
  }

  /** 숫자 roomId가 필요한 자리에 문자열을 보낸 경우의 400을 성공 오퍼레이션과 함께 Swagger에 합친다. */
  @Test
  @DisplayName("채팅방 메시지 숫자가 아닌 roomId 문서화")
  void getMessagesWithMalformedRoomId() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/chat-rooms/{roomId}/messages", "room-id")
                .header(HttpHeaders.AUTHORIZATION, accessToken()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"))
        .andDo(
            ApiDocsErrors.errorSnippet(
                "chat-message-history-malformed-room-id",
                ApiDocsTags.CHATS,
                MESSAGE_HISTORY_SUMMARY,
                MESSAGE_HISTORY_DESCRIPTION,
                messageHistoryPathParameters(),
                MESSAGE_HISTORY_400));
  }

  /** 비참여자는 존재하는 roomId를 입력해도 메시지와 방 존재 여부를 알 수 없음을 문서화한다. */
  @Test
  @DisplayName("채팅방 메시지 비참여자 404 문서화")
  void getMessagesAsOutsider() throws Exception {
    long roomId = createRoomThroughInquiry();
    String outsiderToken = bearer(jwtTokenService.issueAccessToken(999L));

    mockMvc
        .perform(
            get("/api/v1/chat-rooms/{roomId}/messages", roomId)
                .header(HttpHeaders.AUTHORIZATION, outsiderToken))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("CHAT_ROOM_NOT_FOUND"))
        .andDo(
            ApiDocsErrors.errorSnippet(
                "chat-message-history-not-found",
                ApiDocsTags.CHATS,
                MESSAGE_HISTORY_SUMMARY,
                MESSAGE_HISTORY_DESCRIPTION,
                messageHistoryPathParameters(),
                MESSAGE_HISTORY_404));
  }

  /** 토큰이 없는 메시지 이력 조회가 컨트롤러 전에 401로 차단되는 계약을 문서화한다. */
  @Test
  @DisplayName("채팅방 메시지 인증 누락 문서화")
  void getMessagesUnauthenticated() throws Exception {
    mockMvc
        .perform(get("/api/v1/chat-rooms/{roomId}/messages", 556L))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
        .andDo(
            ApiDocsErrors.errorSnippet(
                "chat-message-history-unauthenticated",
                ApiDocsTags.CHATS,
                MESSAGE_HISTORY_SUMMARY,
                MESSAGE_HISTORY_DESCRIPTION,
                messageHistoryPathParameters(),
                MESSAGE_HISTORY_401));
  }

  /** 만료 토큰을 일반 인증 누락과 구분해 프론트가 토큰 재발급 흐름으로 연결할 수 있게 한다. */
  @Test
  @DisplayName("채팅방 메시지 만료 토큰 문서화")
  void getMessagesWithExpiredToken() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/chat-rooms/{roomId}/messages", 556L)
                .header(HttpHeaders.AUTHORIZATION, expiredAccessTokenHeader()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("TOKEN_EXPIRED"))
        .andDo(
            ApiDocsErrors.errorSnippet(
                "chat-message-history-token-expired",
                ApiDocsTags.CHATS,
                MESSAGE_HISTORY_SUMMARY,
                MESSAGE_HISTORY_DESCRIPTION,
                messageHistoryPathParameters(),
                MESSAGE_HISTORY_401));
  }

  /** 온보딩 임시 토큰으로 정식 채팅 메시지를 조회할 수 없다는 403 계약을 문서화한다. */
  @Test
  @DisplayName("채팅방 메시지 온보딩 사용자 거부 문서화")
  void getMessagesWithOnboardingToken() throws Exception {
    String onboardingToken = bearer(jwtTokenService.issueOnboardingToken(TENANT_ID));

    mockMvc
        .perform(
            get("/api/v1/chat-rooms/{roomId}/messages", 556L)
                .header(HttpHeaders.AUTHORIZATION, onboardingToken))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("AUTH_ONBOARDING_REQUIRED"))
        .andDo(
            ApiDocsErrors.errorSnippet(
                "chat-message-history-onboarding-required",
                ApiDocsTags.CHATS,
                MESSAGE_HISTORY_SUMMARY,
                MESSAGE_HISTORY_DESCRIPTION,
                messageHistoryPathParameters(),
                MESSAGE_HISTORY_403));
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
                "chat-inquiry",
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

  /** 문의하기도 만료 토큰과 온보딩 미완료를 별도 예시로 제공해 프론트의 재로그인·온보딩 분기를 명확히 한다. */
  @Test
  @DisplayName("문의 만료 토큰과 온보딩 사용자 문서화")
  void createInquiryAuthenticationErrors() throws Exception {
    performError(
        inquiryRequest().header(HttpHeaders.AUTHORIZATION, expiredAccessTokenHeader()),
        status().isUnauthorized(),
        "TOKEN_EXPIRED",
        "chat-inquiry-token-expired",
        INQUIRY_401);

    performError(
        inquiryRequest()
            .header(
                HttpHeaders.AUTHORIZATION, bearer(jwtTokenService.issueOnboardingToken(TENANT_ID))),
        status().isForbidden(),
        "AUTH_ONBOARDING_REQUIRED",
        "chat-inquiry-onboarding-required",
        INQUIRY_403);
  }

  /** 현재 사용자의 access token을 Bearer 헤더 값으로 만든다. */
  private String accessToken() {
    return bearer(jwtTokenService.issueAccessToken(TENANT_ID));
  }

  /** Swagger 401 예시가 실제 보안 필터의 TOKEN_EXPIRED 응답에서 생성되도록 만료 토큰 헤더를 만든다. */
  private String expiredAccessTokenHeader() {
    return bearer(expiredAccessToken(jwtProperties));
  }

  /** 실제 문의 endpoint로 목록 테스트용 채팅방과 참여자 두 명을 만든다. */
  private long createRoomThroughInquiry() throws Exception {
    String response =
        mockMvc
            .perform(inquiryRequest().header(HttpHeaders.AUTHORIZATION, accessToken()))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return roomIdFromJsonLine(response);
  }

  /** 저장된 마지막 메시지를 가리키도록 채팅방의 목록용 포인터를 갱신한다. */
  private static ChatRoom roomWithLastMessage(ChatRoom room, Message message) {
    return ChatRoom.builder()
        .id(room.getId())
        .listingId(room.getListingId())
        .tenantId(room.getTenantId())
        .landlordId(room.getLandlordId())
        .category(room.getCategory())
        .listingSnapshot(room.getListingSnapshot())
        .lastMessageId(message.getId())
        .lastMessageAt(message.getSentAt())
        .createdAt(room.getCreatedAt())
        .updatedAt(message.getSentAt())
        .build();
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
