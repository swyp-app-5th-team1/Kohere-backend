package com.kohere.common.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 보안 필터 체인 구성(ADR-0010). 무상태 Bearer 인증 — 세션·CSRF·폼로그인 비활성, {@link JwtAuthenticationFilter}를 인증 필터
 * 앞에 등록한다.
 *
 * <p>보호 경로: (1) 공개(permitAll) — social-login·reissue·actuator health·매물 탐색, (2) 온보딩 스코프 이상 —
 * onboarding·DELETE /users/me(PENDING 탈퇴 허용), (3) 정식 인증(ROLE_USER) — GET/PATCH
 * /users/me·logout·찜·최근 본 매물·예약·채팅 등, (4) <b>게스트 허용(permitAll)</b> — 퀴즈·생활 팁·v2 진단(회원·게스트가 함께 닿는다).
 * PENDING(ROLE_ONBOARDING) 토큰으로 ROLE_USER 자원 접근 시 {@link RestAccessDeniedHandler}가 403
 * AUTH_ONBOARDING_REQUIRED로 응답한다.
 *
 * <p>(4)는 토큰이 오면 {@link JwtAuthenticationFilter}가 세운 주체를 그대로 쓰고, 없으면 주체 없이(userId=null) 통과시킨다(게스트
 * 신원 표현은 {@link AuthPrincipals#userIdOrNull}). <b>만료 토큰은 게스트로 강등하지 않고 기본 401 TOKEN_EXPIRED</b>이며,
 * 신원이 무관한 공개 티어({@link PublicPaths} — 로그인·재발급·health·문서)만 예외로 통과시킨다 — 재발급 요청에 만료된 access 토큰이 실려 와도
 * 막히지 않게 하기 위해서다. 매물 탐색·퀴즈·생활 팁·진단은 PublicPaths가 아니므로 만료 토큰이면 401이다(#181).
 *
 * <p>매처는 선언 순서대로 평가되므로 게스트 허용 줄은 반드시 {@code anyRequest()} 위에 있어야 한다.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

  private final JwtAuthenticationFilter jwtAuthenticationFilter;
  private final RestAuthenticationEntryPoint authenticationEntryPoint;
  private final RestAccessDeniedHandler accessDeniedHandler;

  /**
   * HTTP 요청에 적용할 무상태 보안 필터와 경로별 최소 권한을 구성한다.
   *
   * <p>경로 매처는 위에서부터 처음 일치한 규칙이 적용되므로, 세부적인 공개·ROLE_USER 규칙을 마지막 {@code anyRequest}보다 먼저 둔다.
   *
   * @param http Spring Security HTTP 보안 구성 객체
   * @return 애플리케이션 REST 요청에 적용할 필터 체인
   * @throws Exception Spring Security 구성 생성 실패
   */
  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http.csrf(csrf -> csrf.disable())
        .formLogin(form -> form.disable())
        .httpBasic(basic -> basic.disable())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth
                    // (1) 공개
                    .requestMatchers(
                        HttpMethod.POST, "/api/v1/auth/social-login", "/api/v1/auth/reissue")
                    .permitAll()
                    // 임대인 웹 가입용 연락처 SMS 인증(US-1-13) — 계정이 없는 가입 전 단계라 주체를 세울 수 없다.
                    // 아래 (2)의 /auth/phone/verification-code·/auth/phone/verify는 정확 경로 매처라
                    // 한 세그먼트 깊은 이 경로를 덮지 않지만, 순서가 뒤집혀도 안전하도록 공개 티어에 먼저 둔다.
                    // PublicPaths.ALL에도 같은 두 경로를 등록해야 한다(만료 토큰 401 방지 — #181).
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/v1/auth/phone/signup/verification-code",
                        "/api/v1/auth/phone/signup/verify")
                    .permitAll()
                    // 임대인 웹 회원가입(US-1-11)·로그인(US-1-12) — 계정을 만들거나 아직 토큰을 받기 전이라 주체가
                    // 있을 수 없다. 아래 (2)·(3)의 /auth/* 매처는 전부 정확 경로라 이 두 경로를 덮지 않지만,
                    // 순서가 뒤집혀도 안전하도록 공개 티어에 둔다.
                    // PublicPaths.ALL에도 같은 두 경로를 등록해야 한다(만료 토큰 401 방지 — #181).
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/signup", "/api/v1/auth/login")
                    .permitAll()
                    // WebSocket handshake는 통로만 만드는 HTTP upgrade 요청이다. 실제 사용자는 직후
                    // STOMP CONNECT의 Bearer JWT로 다시 인증하므로 이 한 경로만 공개한다.
                    .requestMatchers("/actuator/health", "/swagger-ui/**", "/ws/chat")
                    .permitAll()
                    // 도로명 주소 검색(ADR-0042)과 인근 역 검색(ADR-0044)은 등록 폼 전용이라
                    // 같은 /api/v1/listings/* 아래지만 공개하지 않는다.
                    // 아래 공개 매처보다 반드시 먼저 선언한다 — 먼저 매칭된 규칙이 이기므로 순서가 뒤집히면
                    // 이 인증 요구가 통째로 무시되고 인증 없이 외부 API 쿼터를 소모하는 프록시가 된다.
                    // (/stations/nearby는 두 세그먼트라 공개 매처에 안 걸리지만, 명시하지 않으면
                    //  anyRequest().authenticated()로 떨어져 온보딩 토큰이 통과한다.)
                    // 임대인 여부(userType=LANDLORD)는 매처로 표현할 수 없어 서비스가 재검사한다(403).
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/v1/listings/addresses",
                        "/api/v1/listings/stations",
                        "/api/v1/listings/stations/nearby")
                    .hasRole("USER")
                    // 매물 탐색은 가입 전에도 사용할 수 있는 공개 기능이다. HTTP method를 GET으로 한정하고 한 단계
                    // 하위 경로만 열어 /{listingId}/favorite·/{listingId}/bookings 같은 사용자 액션은 공개하지 않는다.
                    // /listings/*는 v1이 map·search·places·{listingId}를, v2가 map·search·{listingId}를
                    // 덮는다
                    // (places는 v2에 없다 — 매물 데이터를 쓰지 않아 v1에 남겼다).
                    // v1은 빈 결과만 주지만(ADR-0040) 매처는 남긴다 — 401로 바뀌면 구버전 앱이 빈 화면 대신
                    // 로그인 만료로 오인한다. 장소 후보 검색(/listings/places)은 v1에서 계속 동작한다.
                    .requestMatchers(HttpMethod.GET, "/api/v1/listings", "/api/v1/listings/*")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v2/listings", "/api/v2/listings/*")
                    .permitAll()
                    // (2) 온보딩 스코프 이상 허용 — 약관 동의·연락처 인증·온보딩 흐름(PENDING/TERMS_AGREED 토큰 허용).
                    // 세입자 이메일 인증(/auth/email/**)은 #192에서 온보딩 흐름에서 제외돼 정식(ACTIVE) 전용으로 반전 → (3)으로
                    // 이동.
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/v1/auth/terms",
                        "/api/v1/auth/phone/verification-code",
                        "/api/v1/auth/phone/verify",
                        "/api/v1/auth/onboarding",
                        "/api/v1/auth/landlord/onboarding")
                    .authenticated()
                    .requestMatchers(HttpMethod.DELETE, "/api/v1/users/me")
                    .authenticated()
                    // (3) 정식 인증(ROLE_USER) — 온보딩 완료(ACTIVE) 사용자만. 사업자번호 검증은 온보딩 후 임대인이
                    // 호출(ADR-0033).
                    // 세입자 이메일 인증은 온보딩 완료 후 호출하는 API라 정식 토큰 전용이다(#192).
                    .requestMatchers("/api/v1/users/me")
                    .hasRole("USER")
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/v1/auth/email/verification-code",
                        "/api/v1/auth/email/verify",
                        "/api/v1/auth/business/verify",
                        "/api/v1/auth/logout")
                    .hasRole("USER")
                    // 매물 등록 — ACTIVE(ROLE_USER)만. LANDLORD 여부는 서비스에서 재검사한다(403).
                    .requestMatchers(HttpMethod.POST, "/api/v2/listings", "/api/v2/listings/images")
                    .hasRole("USER")
                    // 찜과 최근 본 매물은 사용자별 데이터를 읽고 변경하므로 ACTIVE(ROLE_USER) 사용자만 허용한다.
                    // 명시하지 않고 anyRequest().authenticated()에 맡기면 ROLE_ONBOARDING 토큰도 통과할 수 있다.
                    // v1은 빈 결과·404만 주지만(ADR-0040) 인가는 그대로 둔다 — 빈 목록과 비로그인은 구버전 앱에서
                    // 다르게 처리된다.
                    .requestMatchers(HttpMethod.POST, "/api/v1/listings/*/favorite")
                    .hasRole("USER")
                    .requestMatchers(HttpMethod.DELETE, "/api/v1/listings/*/favorite")
                    .hasRole("USER")
                    .requestMatchers(HttpMethod.POST, "/api/v2/listings/*/favorite")
                    .hasRole("USER")
                    .requestMatchers(HttpMethod.DELETE, "/api/v2/listings/*/favorite")
                    .hasRole("USER")
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/v1/users/me/favorites",
                        "/api/v1/users/me/recent-listings",
                        "/api/v2/users/me/favorites",
                        "/api/v2/users/me/recent-listings")
                    .hasRole("USER")
                    // 매물 예약(신청) — ACTIVE(ROLE_USER) 세입자만. TENANT 여부는 서비스에서 재검사한다.
                    .requestMatchers(HttpMethod.POST, "/api/v1/listings/*/bookings")
                    .hasRole("USER")
                    .requestMatchers(HttpMethod.GET, "/api/v1/bookings", "/api/v1/bookings/*")
                    .hasRole("USER")
                    // 매물 문의는 JWT 사용자와 listing 정본의 임대인을 이용해 사용자별 채팅방을 만들므로 ACTIVE 사용자만
                    // 허용한다. body에서 사용자 번호를 받지 않더라도 온보딩 토큰이 통과하면 미완성 계정으로 참여자 행이
                    // 생길 수 있어, 실제 유스케이스를 구현하기 전에 HTTP 경계에서 먼저 차단한다.
                    .requestMatchers(HttpMethod.POST, "/api/v1/listings/*/inquiries")
                    .hasRole("USER")
                    // 채팅방 목록·상세·메시지 이력 등 /chat-rooms 아래 REST는 모두 개인 대화 데이터를 다룬다.
                    // 참여자 여부는 서비스가 방마다 추가 검증하지만, 여기서는 최소 조건인 ROLE_USER를 공통 적용한다.
                    // WebSocket handshake는 위 공개 경로에서 통로만 열고, STOMP 프레임 인증은 채팅 모듈의
                    // ChannelInterceptor가 별도로 담당하므로 이 REST 매처에 섞지 않는다.
                    .requestMatchers("/api/v1/chat-rooms/**")
                    .hasRole("USER")
                    // 신고 사유 조회와 신고 접수에는 사용자 언어·신고자 식별자가 필요하다. 일반 사용자 신고 API 전체를
                    // ROLE_USER로 묶어 온보딩 중인 계정이 운영 처리 데이터에 들어오는 것을 막는다. 관리자 검토 API는
                    // 후속 `/api/v1/admin/**` 경계로 분리되므로 이 사용자 경로 매처의 영향을 받지 않는다.
                    .requestMatchers("/api/v1/reports/**")
                    .hasRole("USER")
                    // 예약 내역 관리(삭제·차단·신고) — 조회 매처는 GET·단일 세그먼트라 신규 경로를 덮지 못한다.
                    // 명시하지 않으면 anyRequest().authenticated()로 떨어져 온보딩(ROLE_ONBOARDING) 토큰이 통과한다.
                    .requestMatchers(HttpMethod.DELETE, "/api/v1/bookings/*")
                    .hasRole("USER")
                    .requestMatchers(
                        HttpMethod.POST, "/api/v1/bookings/*/block", "/api/v1/bookings/*/report")
                    .hasRole("USER")
                    // 차단 목록·해제 — /api/v1/users/me 정확 매처가 /me/blocks를 덮지 않아 별도 매처가 필요하다.
                    .requestMatchers("/api/v1/users/me/blocks", "/api/v1/users/me/blocks/*")
                    .hasRole("USER")
                    // (4) 게스트 허용(permitAll) — 회원·비회원이 함께 닿는다(#181). 만료 토큰이 조용히 게스트로 강등되지
                    // 않게 막는 가드는 JwtAuthenticationFilter에 있다(공개 티어 PublicPaths만 예외).
                    // 생활 팁 — 비회원 허용(US-8·08-life-tips.md). 게스트는 users 행이 없어 표시 언어를 en으로 고정하고,
                    // 세입자 한정 게이트는 제거해 임대인도 조회할 수 있다.
                    .requestMatchers("/api/v1/life-tips/**")
                    .permitAll()
                    // 학습 퀴즈 — 비회원 허용(06-gamification.md). 역할 게이트 없이 누구나 풀 수 있고, 포인트 적립만
                    // 회원(userId != null)에게 적용한다.
                    .requestMatchers("/api/v1/quizzes/**")
                    .permitAll()
                    // v2 서버 주도 진단 — 비회원 허용(US-2-7). v1 진단(/api/v1/diagnoses/**)은 매처를 추가하지 않고
                    // anyRequest().authenticated()에 남겨 회원 전용으로 유지한다.
                    .requestMatchers("/api/v2/diagnoses/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            ex ->
                ex.authenticationEntryPoint(authenticationEntryPoint)
                    .accessDeniedHandler(accessDeniedHandler))
        .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }
}
