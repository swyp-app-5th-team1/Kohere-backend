package com.kohere.auth.presentation;

import com.kohere.auth.application.FindEmailService;
import com.kohere.auth.application.dto.FindEmailResponse;
import com.kohere.auth.presentation.dto.FindEmailRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 가입 이메일 찾기 REST 컨트롤러(임대인 웹·비로그인, US-1-16). 입력 검증·DTO 변환만 담당하고 비즈니스 로직은 {@link FindEmailService}에
 * 위임한다. 공통 래퍼는 {@link com.kohere.common.response.ApiResponseWrapper}가 자동 적용한다(ADR-0013).
 *
 * <p><b>왜 조회인데 POST인가</b> — 요청 본문에 인증된 연락처와 이름이 실린다. GET의 쿼리스트링은 프록시·서버 액세스 로그와 브라우저 히스토리에 그대로 남아,
 * 조회 한 번이 로그 곳곳에 개인정보를 흩뿌린다. 게다가 이 호출은 성공 시 <b>검증 마커를 소비</b>하는 상태 변경이라 멱등하지도 않다.
 *
 * <p><b>왜 {@link AuthController}가 아닌가</b> — 그쪽 엔드포인트는 전부 단일 협력자({@code AuthService})에 위임하고 소셜 로그인으로
 * 시작하는 하나의 계정 생애주기에 속한다. 이 경로는 협력자가 다르고(이메일 찾기 전용 서비스) 계정에 <b>들어가지 못하게 된 사람</b>이 부르는 복구 트랙이다.
 *
 * <p>permitAll이다 — 로그인 ID를 모르는 단계라 인증할 수단 자체가 없다. {@code SecurityConfig}와 {@code PublicPaths.ALL}
 * 양쪽에 등록돼 있다.
 *
 * <p>스펙: docs/api/specs/01-auth-onboarding.md §1-7.
 */
@RestController
@RequestMapping("/api/v1/auth/email")
@RequiredArgsConstructor
public class FindEmailController {

  private final FindEmailService findEmailService;

  @PostMapping("/find")
  public FindEmailResponse findEmail(@Valid @RequestBody FindEmailRequest request) {
    return findEmailService.findEmail(request.phoneNumber(), request.name());
  }
}
