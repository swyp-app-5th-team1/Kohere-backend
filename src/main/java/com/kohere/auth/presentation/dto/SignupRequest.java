package com.kohere.auth.presentation.dto;

import com.kohere.common.request.PhoneNumbers;
import com.kohere.common.request.RequestDates;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 임대인 웹 회원가입 요청 DTO(POST /api/v1/auth/signup, 비로그인). 가입 폼 한 페이지의 값을 전부 담는다 — <b>연동 여부와 무관하게 항상 같은
 * 필드</b>를 받는다(화면이 하나이고 분기가 없다, US-1-11).
 *
 * <p>약관 3종은 앱 {@link TermsRequest}와 같은 모양이다 — 필수 2종({@code termsOfServiceAgreed}·{@code
 * privacyPolicyAgreed})은 {@code @NotNull}로 <b>존재만</b> 강제하고 {@code false}는 응용 계층이 422 {@code
 * AUTH_REQUIRED_AGREEMENT_MISSING}으로 끊는다(형식이 아니라 비즈니스 규칙 위반이라 400이 아니다). {@code marketingAgreed}는
 * 선택이다.
 *
 * <p>{@code birthDate}는 {@code String}으로 받는다 — {@code LocalDate}로 선언하면 형식 오류가 역직렬화에서 걸려 {@code
 * MALFORMED_REQUEST}가 되고 어느 필드가 문제인지 남지 않는다. 응용 계층이 {@link RequestDates#parsePast}로 파싱해 {@code
 * INVALID_INPUT} + {@code errors[].field}로 돌려준다(세입자·임대인 온보딩과 같은 분업).
 *
 * <p>{@code phoneNumber}는 하이픈이 있어도 없어도 되며 서버가 숫자만 남겨 정규화한다({@link PhoneNumbers}). 이 값은 <b>계정 연동의
 * 유일한 매칭 키</b>이지만 인증 수단은 아니다 — 소유 증명은 선행 SMS 인증(§1-1·§1-2)이 담당한다.
 *
 * <p>{@code name}·{@code email}의 길이 상한은 저장 컬럼({@code local_accounts.name} VARCHAR(200) · {@code
 * local_accounts.email} VARCHAR(255), V22)과 맞춘다. 상한이 없으면 300자짜리 값이 모든 게이트를 통과한 뒤 INSERT에서 {@code
 * DataIntegrityViolationException}으로 죽어 <b>400이어야 할 입력 오류가 500</b>이 된다 — 비로그인 경로라 그 길을 밟기도 쉽다.
 *
 * <p>docs/api/specs/01-auth-onboarding.md §1-3 · ADR-0047.
 */
public record SignupRequest(
    @NotBlank @Size(max = 200) String name,
    @NotBlank String birthDate,
    @NotBlank @Pattern(regexp = PhoneNumbers.PATTERN) String phoneNumber,
    @NotBlank @Size(max = 255) @Email String email,
    @NotBlank @Pattern(regexp = SignupRequest.PASSWORD_PATTERN) String password,
    @NotNull Boolean termsOfServiceAgreed,
    @NotNull Boolean privacyPolicyAgreed,
    Boolean marketingAgreed) {

  /**
   * 웹 로그인 비밀번호 정책(#229 D1 · ADR-0047 §1) — <b>영문자 1자 이상 + 숫자 1자 이상 + ASCII 특수문자 1자 이상, 길이 8~20</b>.
   *
   * <p>앞의 세 전방탐색이 "각 종류가 하나 이상"을, 마지막 문자 클래스가 <b>허용 집합 자체</b>를 정한다. 허용 집합에 공백이 없으므로 공백·탭이 자동으로
   * 걸러지고, 한글·이모지처럼 ASCII 밖 문자도 같은 이유로 거부된다 — "공백 불허"를 따로 부정 탐색으로 쓰지 않고 화이트리스트 하나로 끝낸다.
   *
   * <p>특수문자는 개별 나열이 아니라 <b>연속 코드포인트 범위 넷</b>으로 적었고, 그 넷을 합치면 <b>ASCII 구두점 32자 전부</b>가 된다(공백 0x20은
   * 어느 범위에도 들어가지 않는다). 목록을 손으로 나열하면 빠뜨린 문자 하나가 "왜 내 비밀번호는 안 되지"로 돌아온다.
   *
   * <p>상한 20자는 BCrypt의 72바이트 한계와 무관한 <b>정책 선택</b>이다. 길이를 조정할 때 바꿀 곳은 이 수량자 하나와 두 번들의 문구뿐이다.
   *
   * <p>위반 시 {@code 400 INVALID_INPUT} + {@code errors[].field=password}이며, 사유 문구는 {@code
   * Pattern.signupRequest.password} 키로 두 번들(영어·한국어)에서 해소한다 — 그래서 {@code @Pattern}에 {@code message}
   * 속성을 두지 않는다(ADR-0030: {@code MessageSource}가 키를 먼저 찾아 이기므로 애너테이션 문구는 죽은 코드가 된다).
   */
  static final String PASSWORD_PATTERN =
      "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[!-/:-@\\[-`{-~])[A-Za-z\\d!-/:-@\\[-`{-~]{8,20}$";
}
