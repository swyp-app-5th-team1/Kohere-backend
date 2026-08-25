package com.kohere.auth.presentation;

import com.kohere.auth.application.FindEmailPhoneVerificationService;
import com.kohere.auth.application.dto.FindEmailPhoneVerificationCodeResponse;
import com.kohere.auth.application.dto.FindEmailPhoneVerifyResponse;
import com.kohere.auth.presentation.dto.FindEmailPhoneVerificationCodeRequest;
import com.kohere.auth.presentation.dto.FindEmailPhoneVerifyRequest;
import com.kohere.common.request.ClientIps;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 이메일 찾기용 연락처(SMS) 인증 REST 컨트롤러(임대인 웹·비로그인, US-1-16). 입력 검증·DTO 변환과 <b>호출자 IP 추출</b>만 담당하고 비즈니스 로직은
 * 응용 계층에 위임한다(docs/convention/code-style.md §3-3). 공통 래퍼는 {@link
 * com.kohere.common.response.ApiResponseWrapper}가 자동 적용한다(ADR-0013).
 *
 * <p><b>왜 {@link SignupPhoneVerificationController}에 얹지 않는가</b> — 두 컨트롤러의 메서드 모양은 같지만 <b>경로 프리픽스가 곧
 * 키스페이스</b>다({@code /auth/phone/signup} vs {@code /auth/phone/find-email}). 한 컨트롤러가 두 접두사를 다루면 협력자도
 * 둘이 되고, 그 순간 "가입용 서비스를 부를 자리에 이메일 찾기용 서비스를 부르는" 한 글자 실수가 가능해진다 — 그 실수는 컴파일도 되고 테스트도 통과하며, 남는 결과는
 * 마커 하나로 두 게이트가 열리는 것이다. 채널이 갈리면 클래스도 가른다.
 *
 * <p>두 경로 모두 <b>permitAll</b>이다 — {@code SecurityConfig}의 공개 티어와 {@code PublicPaths.ALL} <b>양쪽</b>에
 * 등록돼 있다. 한쪽만 등록하면 <b>만료된 access 토큰이 남은 브라우저에서만</b> 401 {@code TOKEN_EXPIRED}가 나고, 토큰 없이 부르는
 * 로컬·테스트는 전부 초록이라 잡히지 않는다(#181이 고친 버그). 로그인하지 못해 들어오는 화면이라 만료 토큰이 남아 있을 확률이 가장 높은 자리다.
 *
 * <p>스펙: docs/api/specs/01-auth-onboarding.md §1-5·§1-6.
 */
@RestController
@RequestMapping("/api/v1/auth/phone/find-email")
@RequiredArgsConstructor
public class FindEmailPhoneVerificationController {

  private final FindEmailPhoneVerificationService findEmailPhoneVerificationService;

  /**
   * 호출자 IP는 {@link ClientIps}가 뽑는다 — 추출 규칙이 경로마다 달라지면 같은 호출자가 엔드포인트마다 다른 버킷에 들어가 한도가 의도대로 걸리지 않는다.
   * 서블릿 타입은 여기까지만 오고 응용 계층에는 문자열만 내려간다.
   */
  @PostMapping("/verification-code")
  public FindEmailPhoneVerificationCodeResponse sendCode(
      @Valid @RequestBody FindEmailPhoneVerificationCodeRequest request,
      HttpServletRequest servletRequest) {
    return findEmailPhoneVerificationService.sendCode(
        request.phoneNumber(), ClientIps.resolve(servletRequest));
  }

  @PostMapping("/verify")
  public FindEmailPhoneVerifyResponse verify(
      @Valid @RequestBody FindEmailPhoneVerifyRequest request) {
    return findEmailPhoneVerificationService.verify(request.phoneNumber(), request.code());
  }
}
