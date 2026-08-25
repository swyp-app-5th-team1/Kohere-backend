package com.kohere.auth.presentation;

import com.kohere.auth.application.WebPasswordResetService;
import com.kohere.auth.application.dto.PasswordResetLinkResponse;
import com.kohere.auth.application.dto.PasswordResetTokenVerifyResponse;
import com.kohere.auth.presentation.dto.PasswordResetLinkRequest;
import com.kohere.auth.presentation.dto.PasswordResetRequest;
import com.kohere.auth.presentation.dto.PasswordResetTokenVerifyRequest;
import com.kohere.common.request.ClientIps;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 임대인 웹 비밀번호 재설정(= 계정 잠금 해제) REST 컨트롤러 — 링크 발송(§1-8) · 토큰 사전 확인(§1-9) · 확정(§1-10), US-1-17. 입력
 * 검증·DTO 변환만 담당하고 비즈니스 로직은 응용 계층에 위임한다. 성공 응답의 공통 래퍼는 {@link
 * com.kohere.common.response.ApiResponseWrapper}가 자동 적용한다(ADR-0013).
 *
 * <p><b>기능 토글이 컨트롤러에 붙어 있다</b>({@code app.auth.web.password-reset.enabled}). 운영에는 임대인 웹(SPA)이 없어
 * 링크가 도착할 곳 자체가 없으므로, 꺼진 환경에서는 <b>경로가 존재하지 않는 것</b>(404)이 맞다 — 빈만 빼고 경로를 열어 두면 "메일을 보냈다"는 화면 뒤에서 아무
 * 일도 일어나지 않거나, 링크가 아무 데도 가지 않는 base URL로 조립된다.
 *
 * <p><b>서비스·발송기·저장소에는 조건을 걸지 않는다.</b> 조건을 아래 계층까지 내리면 토글이 꺼진 환경에서 빈이 사라져, 그 빈을 주입받는 무언가가 생기는 날
 * <b>기동이 깨진다</b> — 그때 나오는 것은 "재설정이 꺼져 있다"가 아니라 원인을 알기 어려운 {@code NoSuchBeanDefinitionException}이다.
 * 진입점 하나만 닫으면 나머지는 그냥 아무도 부르지 않는 코드로 남는다(도달 경로가 없다는 것이 곧 비활성이다).
 *
 * <p>경로는 <b>permitAll</b>이다 — 비밀번호를 모르거나 계정이 잠긴 상태라 인증할 수단이 없다. {@code SecurityConfig}의 공개 티어와
 * {@code PublicPaths.ALL} <b>양쪽</b>에 등록돼 있다(한쪽만 넣으면 토큰 없이 부르는 로컬·통합 테스트는 전부 초록이고, 만료된 access 토큰이 남은
 * 브라우저에서만 401 {@code TOKEN_EXPIRED}가 난다 — #181이 고친 것과 같은 버그).
 *
 * <p>스펙: docs/api/specs/01-auth-onboarding.md §1-8·§1-9·§1-10.
 */
@RestController
@RequestMapping("/api/v1/auth/password")
@ConditionalOnProperty(
    prefix = "app.auth.web.password-reset",
    name = "enabled",
    havingValue = "true")
@RequiredArgsConstructor
public class WebPasswordResetController {

  private final WebPasswordResetService webPasswordResetService;

  /**
   * 재설정 링크 발송. <b>가입 여부와 무관하게 같은 200</b>이고, 응답에는 링크 유효 시간만 담긴다(§1-8).
   *
   * <p><b>호출자 IP를 여기서 뽑아 넘긴다</b>({@link ClientIps}) — 발송 레이트리밋의 두 축 중 하나다. 다른 permitAll 경로와 같은 추출
   * 규칙을 공유해야 같은 호출자가 경로마다 다른 버킷에 들어가지 않는다. {@link HttpServletRequest}는 이 계층에서 문자열로 바뀌어 응용 계층에는 넘어가지
   * 않는다.
   */
  @PostMapping("/reset-link")
  public PasswordResetLinkResponse sendResetLink(
      @Valid @RequestBody PasswordResetLinkRequest request, HttpServletRequest servletRequest) {
    return webPasswordResetService.sendResetLink(
        request.email(), ClientIps.resolve(servletRequest));
  }

  /**
   * 토큰 사전 확인 — <b>토큰을 소비하지 않는다</b>(§1-9). SPA가 재설정 화면에 도착하자마자 부르며, 링크 프리페치로 미리 열려도 토큰이 죽지 않아야 한다.
   */
  @PostMapping("/reset-token/verify")
  public PasswordResetTokenVerifyResponse verifyToken(
      @Valid @RequestBody PasswordResetTokenVerifyRequest request) {
    return webPasswordResetService.verifyToken(request.token());
  }

  /**
   * 재설정 확정 — 비밀번호 교체 · 잠금 해제 · 실패 카운터 초기화 · 기존 세션 전량 무효화 · 토큰 소비를 한 번에 끝낸다(§1-10).
   *
   * <p><b>204이고 {@code Set-Cookie}가 없다.</b> 다른 웹 트랙 핸들러({@link WebAuthController})가 refresh 쿠키를 싣는
   * 것과 달리 여기서는 <b>세션을 만들지 않는다</b> — 방금 전량 무효화한 자리에 새 세션을 끼워 넣으면 유출된 링크를 주운 쪽에 로그인 상태까지 얹어 주는 셈이다.
   * 클라이언트는 204를 받으면 로그인 화면(§1-4)으로 보낸다.
   *
   * <p>반환 타입이 {@code void}라 본문이 없고, 공통 래퍼는 본문 없는 응답을 건드리지 않는다.
   */
  @PostMapping("/reset")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void resetPassword(@Valid @RequestBody PasswordResetRequest request) {
    webPasswordResetService.resetPassword(request.token(), request.newPassword());
  }
}
