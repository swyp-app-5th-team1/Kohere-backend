package com.kohere.chat;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kohere.chat.application.ChatMessageHistoryService;
import com.kohere.chat.application.ChatRoomDeletionService;
import com.kohere.chat.application.ChatRoomDetailService;
import com.kohere.chat.application.ChatRoomListService;
import com.kohere.chat.application.ChatService;
import com.kohere.chat.application.dto.InquiryResponse;
import com.kohere.chat.presentation.ChatRoomController;
import com.kohere.chat.presentation.ChatStompGuideController;
import com.kohere.chat.presentation.InquiryController;
import com.kohere.common.response.PageInfo;
import com.kohere.common.response.PageResponse;
import com.kohere.common.security.AuthPrincipal;
import com.kohere.common.security.JwtAuthenticationFilter;
import com.kohere.common.security.JwtTokenService;
import com.kohere.common.security.RestAccessDeniedHandler;
import com.kohere.common.security.RestAuthenticationEntryPoint;
import com.kohere.common.security.SecurityConfig;
import com.kohere.report.application.ReportService;
import com.kohere.report.presentation.ReportController;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 채팅·일반 신고 REST 경로의 최소 권한 경계 테스트.
 *
 * <p>컨트롤러와 실제 {@link SecurityConfig}만 올리는 MVC slice로 검증한다. 저장소·MongoDB·MySQL은 이 테스트의 관심사가 아니며,
 * {@link ChatService} 응답을 대체해 ROLE_USER가 보안 필터를 통과해 컨트롤러까지 도달하는지도 분리해서 확인한다.
 *
 * <p>방 참여자 여부, 본인 매물 문의 금지, 신고 대상 검증 같은 객체 단위 권한은 각 기능의 서비스 테스트에서 다룬다. 여기서는 채팅과 일반 사용자 신고 REST에 공통인
 * "온보딩을 완료한 사용자" 조건만 검증한다.
 */
@WebMvcTest(
    controllers = {
      ChatRoomController.class,
      ChatStompGuideController.class,
      InquiryController.class,
      ReportController.class
    })
// 공통 test 프로필은 Mongock을 끈다. MVC 보안 slice에는 Mongo 연결이 없으므로 마이그레이션 러너를 올리지 않는다.
@ActiveProfiles("test")
@Import({
  SecurityConfig.class,
  JwtAuthenticationFilter.class,
  RestAuthenticationEntryPoint.class,
  RestAccessDeniedHandler.class
})
class ChatAndReportRestSecurityTest {

  private static final String CHAT_ROOMS_PATH = "/api/v1/chat-rooms";
  private static final String STOMP_GUIDE_PATH = "/api/v1/chat/stomp-guide";
  private static final String INQUIRY_PATH = "/api/v1/listings/listing-id/inquiries";
  private static final String REPORT_REASONS_PATH = "/api/v1/reports/reasons";

  @Autowired private MockMvc mockMvc;

  // 컨트롤러 이후의 비즈니스 로직은 보안 매처 검증 대상이 아니므로 성공 응답만 정해 준다.
  @MockitoBean private ChatService chatService;

  // 목록 조회는 별도 읽기 서비스가 담당하므로 보안 테스트에서는 DB 없이 성공 결과만 대체한다.
  @MockitoBean private ChatRoomListService chatRoomListService;

  // 단건 조회도 같은 Controller 생성자에 필요하지만 이 테스트는 공통 ROLE_USER 경계만 확인하므로 서비스는 대체한다.
  @MockitoBean private ChatRoomDetailService chatRoomDetailService;

  // 메시지 이력 조회도 같은 Controller 생성자에 필요하다. 참여자·커서 규칙은 전용 서비스 테스트가 담당한다.
  @MockitoBean private ChatMessageHistoryService chatMessageHistoryService;

  // 사용자별 삭제 서비스의 상태 변경은 전용 테스트가 검증하고 여기서는 DELETE 경로의 ROLE_USER 경계만 확인한다.
  @MockitoBean private ChatRoomDeletionService chatRoomDeletionService;

  // 신고 유스케이스 구현 여부와 무관하게 SecurityConfig의 `/reports/**` 권한만 검증하도록 서비스는 대체한다.
  @MockitoBean private ReportService reportService;

  // MVC slice에는 JWT 설정 전체를 올리지 않는다. 요청에 Bearer 토큰이 없으면 실제 필터는 그대로 통과하며,
  // 테스트 사용자의 ROLE_USER/ROLE_ONBOARDING은 spring-security-test가 SecurityContext에 넣는다.
  @MockitoBean private JwtTokenService jwtTokenService;

  /** 인증 정보가 없으면 개인 채팅방 목록을 컨트롤러에 전달하지 않고 401로 차단하는지 검증한다. */
  @Test
  @DisplayName("채팅방 REST: 토큰이 없으면 401")
  void chatRooms_withoutAuthentication_returnsUnauthorized() throws Exception {
    mockMvc
        .perform(get(CHAT_ROOMS_PATH))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
  }

  /** 온보딩 임시 권한은 인증 자체는 됐지만 정식 사용자 권한이 아니므로 403으로 구분하는지 검증한다. */
  @Test
  @DisplayName("채팅방 REST: ROLE_ONBOARDING은 403")
  void chatRooms_withOnboardingRole_returnsForbidden() throws Exception {
    mockMvc
        .perform(get(CHAT_ROOMS_PATH).with(user("7").roles("ONBOARDING")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("AUTH_ONBOARDING_REQUIRED"));
  }

  /** ROLE_USER가 공통 경계를 통과해 실제 채팅방 목록 컨트롤러 응답을 받는지 검증한다. */
  @Test
  @DisplayName("채팅방 REST: ROLE_USER는 컨트롤러까지 통과")
  void chatRooms_withUserRole_reachesController() throws Exception {
    given(chatRoomListService.listRooms(7L, 0, 20))
        .willReturn(PageResponse.of(List.of(), new PageInfo(0, 20, 0L, 0, false)));

    var authenticatedUser =
        new UsernamePasswordAuthenticationToken(
            new AuthPrincipal(7L, true), null, List.of(new SimpleGrantedAuthority("ROLE_USER")));

    mockMvc
        .perform(get(CHAT_ROOMS_PATH).with(authentication(authenticatedUser)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true));
  }

  /** 채팅방 삭제는 개인 상태 변경이므로 인증되지 않은 사용자가 호출할 수 없다. */
  @Test
  @DisplayName("채팅방 삭제 REST: 토큰이 없으면 401")
  void deleteChatRoom_withoutAuthentication_returnsUnauthorized() throws Exception {
    mockMvc
        .perform(delete("/api/v1/chat-rooms/{roomId}", 556L))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
  }

  /** 정식 사용자만 삭제할 수 있고 성공 시 JSON 없이 204를 받는지 검증한다. */
  @Test
  @DisplayName("채팅방 삭제 REST: ROLE_USER는 204를 받는다")
  void deleteChatRoom_withUserRole_reachesController() throws Exception {
    var authenticatedUser =
        new UsernamePasswordAuthenticationToken(
            new AuthPrincipal(7L, true), null, List.of(new SimpleGrantedAuthority("ROLE_USER")));

    mockMvc
        .perform(
            delete("/api/v1/chat-rooms/{roomId}", 556L).with(authentication(authenticatedUser)))
        .andExpect(status().isNoContent());
  }

  /** STOMP 안내도 실제 채팅 사용자용 계약이므로 인증되지 않은 호출을 401로 차단한다. */
  @Test
  @DisplayName("STOMP 안내 REST: 토큰이 없으면 401")
  void stompGuide_withoutAuthentication_returnsUnauthorized() throws Exception {
    mockMvc
        .perform(get(STOMP_GUIDE_PATH))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
  }

  /** 인증만 끝난 온보딩 계정이 anyRequest 규칙으로 안내 API를 실행하지 못하게 ROLE_USER 경계를 검증한다. */
  @Test
  @DisplayName("STOMP 안내 REST: ROLE_ONBOARDING은 403")
  void stompGuide_withOnboardingRole_returnsForbidden() throws Exception {
    mockMvc
        .perform(get(STOMP_GUIDE_PATH).with(user("7").roles("ONBOARDING")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("AUTH_ONBOARDING_REQUIRED"));
  }

  /** 온보딩을 완료한 사용자는 Swagger 안내 응답까지 정상적으로 조회할 수 있다. */
  @Test
  @DisplayName("STOMP 안내 REST: ROLE_USER는 컨트롤러까지 통과")
  void stompGuide_withUserRole_reachesController() throws Exception {
    var authenticatedUser =
        new UsernamePasswordAuthenticationToken(
            new AuthPrincipal(7L, true), null, List.of(new SimpleGrantedAuthority("ROLE_USER")));

    mockMvc
        .perform(get(STOMP_GUIDE_PATH).with(authentication(authenticatedUser)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.webSocketEndpoint").value("/ws/chat"));
  }

  /** 문의 생성도 사용자별 채팅방을 만드는 보호 작업이므로 익명 요청을 401로 막는지 검증한다. */
  @Test
  @DisplayName("문의 REST: 토큰이 없으면 401")
  void inquiry_withoutAuthentication_returnsUnauthorized() throws Exception {
    mockMvc
        .perform(post(INQUIRY_PATH))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
  }

  /** 문의 경로가 anyRequest().authenticated()로 빠지지 않고 명시적으로 ROLE_USER를 요구하는지 검증한다. */
  @Test
  @DisplayName("문의 REST: ROLE_ONBOARDING은 403")
  void inquiry_withOnboardingRole_returnsForbidden() throws Exception {
    mockMvc
        .perform(post(INQUIRY_PATH).with(user("7").roles("ONBOARDING")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("AUTH_ONBOARDING_REQUIRED"));
  }

  /** ROLE_USER가 문의 컨트롤러에 도달하고 신규 방 계약인 201 응답을 받는지 검증한다. */
  @Test
  @DisplayName("문의 REST: ROLE_USER는 컨트롤러까지 통과")
  void inquiry_withUserRole_reachesController() throws Exception {
    given(chatService.createInquiry(7L, "listing-id")).willReturn(new InquiryResponse(101L, true));

    var authenticatedUser =
        new UsernamePasswordAuthenticationToken(
            new AuthPrincipal(7L, true), null, List.of(new SimpleGrantedAuthority("ROLE_USER")));

    mockMvc
        .perform(post(INQUIRY_PATH).with(authentication(authenticatedUser)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.chatRoomId").value(101L));
  }

  /** 일반 신고 경로도 인증 여부만 보는 기본 규칙으로 빠지지 않고 온보딩 임시 권한을 403으로 차단하는지 검증한다. */
  @Test
  @DisplayName("일반 신고 REST: ROLE_ONBOARDING은 403")
  void reports_withOnboardingRole_returnsForbidden() throws Exception {
    mockMvc
        .perform(get(REPORT_REASONS_PATH).with(user("7").roles("ONBOARDING")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("AUTH_ONBOARDING_REQUIRED"));
  }
}
