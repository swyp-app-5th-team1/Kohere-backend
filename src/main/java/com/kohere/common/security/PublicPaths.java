package com.kohere.common.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.AntPathMatcher;

/**
 * 토큰을 아예 보지 않는 공개 티어 경로 정본(#181). {@link SecurityConfig}의 (1) 공개 티어와 같은 경로 집합이다 — 로그인·재발급·상태
 * 점검·문서처럼 <b>신원이 무관</b>한 경로다.
 *
 * <p><b>용도</b>: {@link JwtAuthenticationFilter}가 만료 토큰을 만났을 때, 기본은 401 {@code TOKEN_EXPIRED}로
 * 끊고(게스트 강등 금지) <b>여기 해당하는 경로만 예외로 통과</b>시킨다. 클라이언트가 모든 요청에 access 토큰을 붙이는 구조라 <b>재발급 요청에 만료된
 * access 토큰이 실려 올 수 있는데</b>, 이때 401로 막으면 재발급 자체가 불가능해져 교착이 된다 — 그래서 공개 티어는 만료 토큰이 실려 와도 무시하고 통과시킨다.
 *
 * <p>게스트 허용 경로(퀴즈·생활 팁·v2 진단)를 <b>열거하지 않는다</b> — 판정 방향이 "공개 경로인가"라, 게스트 허용 경로가 늘어도 이 목록은 그대로다. 목록에서
 * 빠진 경로는 만료 토큰이 401이 되어(fail-closed) <b>조용한 게스트 강등이 아니라 눈에 띄는 실패</b>가 된다.
 */
final class PublicPaths {

  private static final String[] ALL = {
    "/api/v1/auth/social-login",
    "/api/v1/auth/reissue",
    // 임대인 웹 가입용 연락처 SMS 인증(US-1-13) — 계정을 만들기 전에 부르는 경로라 신원이 무관하다.
    // 만료된 access 토큰이 남아 있는 브라우저가 가입 화면에서 401 TOKEN_EXPIRED를 맞지 않게 여기에도 넣는다.
    "/api/v1/auth/phone/signup/verification-code",
    "/api/v1/auth/phone/signup/verify",
    // 임대인 웹 회원가입(US-1-11)·로그인(US-1-12) — 계정을 만들거나 아직 로그인하지 않은 요청이라 신원이
    // 무관하다. 두 화면 모두 로그아웃 후 다시 들어오는 경로라 만료된 access 토큰이 남아 있기 쉽다.
    "/api/v1/auth/signup",
    "/api/v1/auth/login",
    "/actuator/health",
    "/swagger-ui/**",
    // WebSocket handshake는 인증 결과를 만들지 않는 transport 진입점이다. 실수로 남은 HTTP
    // Authorization header는 무시하고, 실제 사용자는 STOMP CONNECT header에서 다시 인증한다.
    "/ws/chat"
  };

  private static final AntPathMatcher MATCHER = new AntPathMatcher();

  private PublicPaths() {}

  /**
   * 요청이 공개 티어 경로에 해당하는지 판정한다.
   *
   * <p>보안 필터는 {@code DispatcherServlet} 앞에서 돌아 파싱된 경로 속성이 아직 없으므로 요청 URI에서 컨텍스트 경로를 걷어내고 직접 매칭한다.
   *
   * @param request 현재 요청
   * @return 공개 티어 경로면 {@code true}
   */
  static boolean matches(HttpServletRequest request) {
    String path = pathOf(request);
    for (String pattern : ALL) {
      if (MATCHER.match(pattern, path)) {
        return true;
      }
    }
    return false;
  }

  /**
   * 현재 요청이 채팅 WebSocket handshake인지 확인한다.
   *
   * <p>다른 공개 경로는 유효한 HTTP Bearer가 있으면 선택적으로 사용자 신원을 세울 수 있지만, 채팅 handshake는 그 값을 인증 근거로 쓰면 안 된다.
   * 브라우저가 임의 HTTP header를 안정적으로 붙일 수 없기 때문에 실제 정본은 STOMP CONNECT의 Bearer token 하나뿐이다.
   */
  static boolean isChatWebSocketHandshake(HttpServletRequest request) {
    return "/ws/chat".equals(pathOf(request));
  }

  /** DispatcherServlet 전 단계에서도 같은 방식으로 비교하도록 context path를 제외한 경로를 만든다. */
  private static String pathOf(HttpServletRequest request) {
    return request.getRequestURI().substring(request.getContextPath().length());
  }
}
