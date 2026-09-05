package com.kohere.notification;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kohere.common.security.AuthPrincipal;
import com.kohere.common.security.JwtAuthenticationFilter;
import com.kohere.common.security.JwtTokenService;
import com.kohere.common.security.RestAccessDeniedHandler;
import com.kohere.common.security.RestAuthenticationEntryPoint;
import com.kohere.common.security.SecurityConfig;
import com.kohere.notification.application.NotificationPreferenceService;
import com.kohere.notification.presentation.NotificationPreferenceController;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 알림 설정 REST가 정식 사용자에게만 열리고 명시적인 boolean만 서비스로 전달하는지 검증한다. */
@WebMvcTest(controllers = NotificationPreferenceController.class)
@ActiveProfiles("test")
@Import({
  SecurityConfig.class,
  JwtAuthenticationFilter.class,
  RestAuthenticationEntryPoint.class,
  RestAccessDeniedHandler.class
})
class NotificationPreferenceRestSecurityTest {

  private static final long USER_ID = 7L;
  private static final String PATH = "/api/v1/users/me/notification-preferences";

  @Autowired private MockMvc mockMvc;

  @MockitoBean private NotificationPreferenceService preferenceService;
  @MockitoBean private JwtTokenService jwtTokenService;

  /** 인증 정보가 없는 조회 요청은 컨트롤러 전에 401로 차단한다. */
  @Test
  void getWithoutAuthenticationReturnsUnauthorized() throws Exception {
    mockMvc
        .perform(get(PATH))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));

    verifyNoInteractions(preferenceService);
  }

  /** 온보딩 임시 권한은 계정 설정에 접근할 수 없어 403을 받는다. */
  @Test
  void getWithOnboardingRoleReturnsForbidden() throws Exception {
    mockMvc
        .perform(get(PATH).with(user("7").roles("ONBOARDING")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("AUTH_ONBOARDING_REQUIRED"));

    verifyNoInteractions(preferenceService);
  }

  /** 정식 사용자의 조회에는 JWT 사용자 번호만 사용하고 공통 응답 봉투로 값을 반환한다. */
  @Test
  void getWithUserRoleReturnsPreference() throws Exception {
    given(preferenceService.isChatPushEnabled(USER_ID)).willReturn(true);

    mockMvc
        .perform(get(PATH).with(authentication(authenticatedUser())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.chatPushEnabled").value(true));

    verify(preferenceService).isChatPushEnabled(USER_ID);
  }

  /** 정식 사용자가 보낸 false를 자기 사용자 번호와 함께 서비스에 전달한다. */
  @Test
  void patchWithUserRoleUpdatesPreference() throws Exception {
    given(preferenceService.updateChatPushEnabled(USER_ID, false)).willReturn(false);

    mockMvc
        .perform(
            patch(PATH)
                .with(authentication(authenticatedUser()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"chatPushEnabled\":false}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.chatPushEnabled").value(false));

    verify(preferenceService).updateChatPushEnabled(USER_ID, false);
  }

  /** 누락된 설정값은 false로 오해하지 않고 요청 검증 단계에서 400으로 거부한다. */
  @Test
  void patchWithoutValueReturnsBadRequest() throws Exception {
    mockMvc
        .perform(
            patch(PATH)
                .with(authentication(authenticatedUser()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));

    verifyNoInteractions(preferenceService);
  }

  /** 컨트롤러가 요구하는 {@link AuthPrincipal}과 ROLE_USER를 가진 테스트 인증 객체를 만든다. */
  private UsernamePasswordAuthenticationToken authenticatedUser() {
    return new UsernamePasswordAuthenticationToken(
        new AuthPrincipal(USER_ID, true), null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
  }
}
