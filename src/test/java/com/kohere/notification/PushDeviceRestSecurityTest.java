package com.kohere.notification;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kohere.common.security.AuthPrincipal;
import com.kohere.common.security.JwtAuthenticationFilter;
import com.kohere.common.security.JwtTokenService;
import com.kohere.common.security.RestAccessDeniedHandler;
import com.kohere.common.security.RestAuthenticationEntryPoint;
import com.kohere.common.security.SecurityConfig;
import com.kohere.notification.application.PushDeviceService;
import com.kohere.notification.domain.PushPlatform;
import com.kohere.notification.presentation.PushDeviceController;
import java.util.List;
import java.util.UUID;
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

/** FCM 기기 등록·삭제 REST가 정식 사용자에게만 열리는지 실제 {@link SecurityConfig}로 검증한다. */
@WebMvcTest(controllers = PushDeviceController.class)
@ActiveProfiles("test")
@Import({
  SecurityConfig.class,
  JwtAuthenticationFilter.class,
  RestAuthenticationEntryPoint.class,
  RestAccessDeniedHandler.class
})
class PushDeviceRestSecurityTest {

  private static final long USER_ID = 7L;
  private static final UUID INSTALLATION_ID =
      UUID.fromString("e7714046-1634-4dc1-a97e-8c1f91a72483");
  private static final String PATH = "/api/v1/users/me/push-devices/{installationId}";
  private static final String REQUEST_BODY =
      "{\"fcmToken\":\"current-fcm-token\",\"platform\":\"IOS\"}";

  @Autowired private MockMvc mockMvc;

  @MockitoBean private PushDeviceService pushDeviceService;

  // MVC slice에서는 Bearer 토큰을 직접 파싱하지 않고 spring-security-test가 인증 주체를 넣는다.
  @MockitoBean private JwtTokenService jwtTokenService;

  /** 인증 정보가 없는 등록 요청을 컨트롤러 전에 401로 차단하는지 확인한다. */
  @Test
  void registerWithoutAuthenticationReturnsUnauthorized() throws Exception {
    mockMvc
        .perform(
            put(PATH, INSTALLATION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(REQUEST_BODY))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
  }

  /** 온보딩 임시 권한은 사용자 푸시 주소를 변경할 수 없어 403을 받는지 확인한다. */
  @Test
  void registerWithOnboardingRoleReturnsForbidden() throws Exception {
    mockMvc
        .perform(
            put(PATH, INSTALLATION_ID)
                .with(user("7").roles("ONBOARDING"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(REQUEST_BODY))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("AUTH_ONBOARDING_REQUIRED"));
  }

  /** 정식 사용자가 등록하면 JWT 사용자 번호만 서비스에 전달하고 204를 반환하는지 확인한다. */
  @Test
  void registerWithUserRoleReachesController() throws Exception {
    mockMvc
        .perform(
            put(PATH, INSTALLATION_ID)
                .with(authentication(authenticatedUser()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(REQUEST_BODY))
        .andExpect(status().isNoContent());

    verify(pushDeviceService)
        .register(USER_ID, INSTALLATION_ID, "current-fcm-token", PushPlatform.IOS);
  }

  /** 빈 FCM 토큰은 서비스에 전달하지 않고 요청 검증 단계에서 400으로 거부하는지 확인한다. */
  @Test
  void registerWithBlankTokenReturnsBadRequest() throws Exception {
    mockMvc
        .perform(
            put(PATH, INSTALLATION_ID)
                .with(authentication(authenticatedUser()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fcmToken\":\"\",\"platform\":\"IOS\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));

    verifyNoInteractions(pushDeviceService);
  }

  /** 정식 사용자가 로그아웃 기기를 삭제하면 사용자 번호와 installation을 서비스에 전달하고 204를 반환하는지 확인한다. */
  @Test
  void deleteWithUserRoleReachesController() throws Exception {
    mockMvc
        .perform(delete(PATH, INSTALLATION_ID).with(authentication(authenticatedUser())))
        .andExpect(status().isNoContent());

    verify(pushDeviceService).delete(USER_ID, INSTALLATION_ID);
  }

  /** 컨트롤러가 요구하는 {@link AuthPrincipal}과 ROLE_USER를 가진 테스트 인증 객체를 만든다. */
  private UsernamePasswordAuthenticationToken authenticatedUser() {
    return new UsernamePasswordAuthenticationToken(
        new AuthPrincipal(USER_ID, true), null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
  }
}
