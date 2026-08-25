package com.kohere.auth.presentation.dto;

import com.kohere.common.request.PhoneNumbers;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 이메일 찾기용 연락처 인증번호 확인 요청 DTO(POST /api/v1/auth/phone/find-email/verify, 임대인 웹·비로그인). {@code
 * phoneNumber}는 인증번호를 발송한 번호와 같아야 한다 — 정규화한 값이 곧 챌린지 키라 발송 때와 하이픈 표기가 달라도 같은 챌린지를 가리킨다({@link
 * PhoneNumbers}).
 *
 * <p>{@code code}에는 길이·형식 제약을 두지 않는다 — 자릿수는 서버 정책({@code app.phone.code-length})이고 검증은 해시 대조다. 가입용
 * {@link SignupPhoneVerifyRequest}가 같은 판단을 이미 내려 두었고, <b>형제 엔드포인트가 같은 입력에 다른 status를 내지 않게</b> 그
 * 선례를 따른다 — 오타 하나가 한쪽에서는 400, 다른 쪽에서는 422가 되면 클라이언트가 화면마다 다른 처리를 해야 한다. 명백히 틀린 입력이 시도 상한을 태우는 것은
 * 대가이지만, 그 상한(5회)과 코드 TTL(5분)이 애초에 무차별 대입 방어의 정본이다. docs/api/specs/01-auth-onboarding.md §1-6.
 */
public record FindEmailPhoneVerifyRequest(
    @NotBlank @Pattern(regexp = PhoneNumbers.PATTERN) String phoneNumber, @NotBlank String code) {}
