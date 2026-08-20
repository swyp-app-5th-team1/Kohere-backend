package com.kohere.common.security;

import com.kohere.common.exception.ErrorCode;
import com.kohere.common.logging.LogFields;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * JWT 검증 횡단 보안 필터(ADR-0010). 매 요청의 {@code Authorization: Bearer} access 토큰을 서명·만료·클레임만 검증(무상태, 저장소
 * 무조회)하고 인증 주체를 {@code SecurityContext}에 주입한다.
 *
 * <p>토큰이 없거나 위조·형식 오류면 컨텍스트를 비운 채(익명) 다음 단계로 넘긴다 — 차단은 인가 단계({@link SecurityConfig})가 결정한다. 권한은 온보딩
 * 스코프로 매핑한다(완료=ROLE_USER, 미완료=ROLE_ONBOARDING).
 *
 * <p><b>만료 토큰은 이 필터가 직접 401 {@code TOKEN_EXPIRED}로 끊는다(#181)</b>. permitAll인 게스트 허용 경로(퀴즈·생활 팁·v2
 * 진단)에서는 {@code AuthenticationException}이 발생하지 않아 {@link RestAuthenticationEntryPoint}가 돌지 않으므로,
 * EntryPoint에 맡기면 만료된 회원이 조용히 게스트로 강등된다. 그래서 <b>만료는 경로와 무관하게 여기서 끊는 것을 기본</b>으로 하고(EntryPoint와 동일한
 * {@link SecurityErrorResponder} 출력), <b>{@link PublicPaths} 공개 티어만 예외</b>로 통과시킨다 — 재발급 요청에 만료된
 * access 토큰이 실려 와도 막히지 않게 하기 위해서다. 판정 방향이 "공개 경로인가"라 게스트 허용 경로가 늘어도 목록은 그대로이고, 목록에서 빠진 경로는 만료 시
 * 401이 되어 (fail-closed) 조용한 강등이 아니라 눈에 띄는 실패가 된다. 게스트로 통과시키는 것은 <b>토큰 미전송·위조·형식 오류</b>뿐이다.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

  /** 만료 토큰 표시 요청 속성. EntryPoint가 401 TOKEN_EXPIRED 구분에 사용. */
  public static final String EXPIRED_ATTRIBUTE = "kohere.jwt.expired";

  private static final String BEARER_PREFIX = "Bearer ";

  /** {@code Long.MAX_VALUE}의 자릿수. 이보다 긴 {@code sub}는 우리가 발급한 값이 아니다. */
  private static final int MAX_USER_ID_DIGITS = 19;

  private final JwtTokenService jwtTokenService;

  /**
   * WebSocket handshake에 우연히 실린 HTTP Bearer는 아예 해석하지 않는다.
   *
   * <p>{@code permitAll}은 이 필터보다 뒤에서 평가된다. 따라서 여기서 명시적으로 제외하지 않으면 유효한 HTTP token이 handshake의 사용자로
   * 먼저 등록되어, 직후 STOMP CONNECT token이라는 단일 인증 정본과 충돌할 수 있다.
   */
  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return PublicPaths.isChatWebSocketHandshake(request);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String header = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (header != null && header.startsWith(BEARER_PREFIX)) {
      String token = header.substring(BEARER_PREFIX.length());
      try {
        AuthPrincipal principal = jwtTokenService.parse(token);
        // 신원을 아는 첫 시점이라 여기서 MDC를 채운다 — MdcLoggingFilter가 anonymous로 깔아둔 값을 덮어
        // 이후 이 요청이 남기는 모든 줄에 실제 userId가 붙는다(ADR-0038). 정리는 MdcLoggingFilter의 finally가 한다.
        MDC.put(LogFields.USER_ID, String.valueOf(principal.userId()));
        MDC.put(LogFields.ONBOARDING, String.valueOf(principal.onboardingCompleted()));
        String role = principal.onboardingCompleted() ? "ROLE_USER" : "ROLE_ONBOARDING";
        var authentication =
            new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority(role)));
        SecurityContextHolder.getContext().setAuthentication(authentication);
      } catch (ExpiredJwtException e) {
        request.setAttribute(EXPIRED_ATTRIBUTE, Boolean.TRUE);
        if (!PublicPaths.matches(request)) {
          // 만료 토큰은 게스트로 강등하지 않고 여기서 401로 끊는다 — permitAll 경로는 EntryPoint가 돌지 않기
          // 때문이다(#181). 공개 티어(로그인·재발급 등)만 예외로 통과시켜 재발급 교착을 막는다.
          // 거부를 실제 사용자에게 귀속시킨다(ADR-0038 용도 3). 통과 분기는 익명으로 처리되므로 채우지 않아
          // MDC가 앱의 실제 취급과 어긋나지 않게 한다.
          putUserIdIfNumeric(e.getClaims());
          SecurityErrorResponder.write(response, ErrorCode.TOKEN_EXPIRED);
          return;
        }
      } catch (JwtException | IllegalArgumentException e) {
        // 위조·형식 오류 → 익명으로 통과(차단은 인가 단계).
        // 여기서는 MDC를 절대 채우지 않는다 — 서명 검증이 실패해 sub가 검증되지 않은 공격자 입력이다.
        // 채우면 감사 로그의 신원이 위조 가능해지고(용도 3 무력화), 콘솔 appender는 값을 그대로 써서
        // 개행이 섞인 sub가 가짜 로그 줄을 만든다.
      }
    }
    filterChain.doFilter(request, response);
  }

  /**
   * 만료 토큰의 {@code sub}를 MDC {@code userId}에 채운다(ADR-0038 용도 3).
   *
   * <p><b>이 경로만 안전한 이유</b>: JJWT는 서명을 먼저 검증하고 그 뒤에 {@code exp}를 본다 — 서명이 틀리면 {@code
   * SignatureException}({@link JwtException})이지 {@link ExpiredJwtException}이 아니다. 따라서 {@code
   * ExpiredJwtException}이 던져졌다는 것 자체가 <b>서명이 유효했다</b>는 뜻이라 클레임을 신뢰할 수 있다(만료됐을 뿐이다).
   *
   * <p>그럼에도 숫자만 통과시킨다. 발급 경로가 바뀌어 비숫자 {@code sub}가 서명될 가능성에 대한 심층 방어이고, 개행이 섞인 값이 콘솔 appender에 그대로
   * 실려 가짜 로그 줄을 만드는 것과 과대 값이 MDC를 타고 그 요청의 모든 줄에 복제되는 것을 함께 막는다.
   */
  private static void putUserIdIfNumeric(Claims claims) {
    if (claims == null) {
      return;
    }
    String subject = claims.getSubject();
    if (subject == null || subject.isEmpty() || subject.length() > MAX_USER_ID_DIGITS) {
      return;
    }
    for (int i = 0; i < subject.length(); i++) {
      if (!Character.isDigit(subject.charAt(i))) {
        return;
      }
    }
    MDC.put(LogFields.USER_ID, subject);
  }
}
