package com.kohere.user.presentation.dto;

import com.kohere.user.domain.Gender;
import com.kohere.user.domain.Occupation;
import com.kohere.user.domain.VisaType;
import jakarta.validation.constraints.Past;
import java.time.LocalDate;

/**
 * 내 프로필 부분 수정 요청 DTO. 모든 필드가 선택이며, 전송한 필드만 변경하고 미전송 필드는 유지한다(미전송 ≠ 값 비움). {@code email}·{@code
 * nickname}은 이 경로로 수정하지 않는다(이메일은 재인증, 닉네임은 시스템 배정).
 *
 * <p>수정 가능 필드는 {@code userType}에 따라 갈린다 — 세입자(TENANT)는 {@code firstName}·{@code lastName}·성별·생년월일·
 * 국적·직업·비자정보·{@code marketingAgreed}를, 임대인(LANDLORD)은 {@code name}·{@code phoneNumber}·{@code
 * marketingAgreed}만 수정한다. 임대인의 {@code phoneNumber} 변경은 새 번호를 SMS로 재인증(§4-1·§4-2)한 뒤에만 반영되며,
 * 미인증·불일치는 422 {@code AUTH_PHONE_NOT_VERIFIED}다(ADR-0034). {@code name}은 임대인 전체 이름으로 {@code
 * FullName.firstName}에 매핑한다.
 *
 * <p>docs/api/specs/01-auth-onboarding.md §9 PATCH /users/me 요청 스키마. {@code country}는 ISO 코드.
 * {@code gender}·{@code occupation}·{@code visaType} 허용 외 enum 문자열·{@code birthDate} 형식 위반은 역직렬화
 * 단계에서 거부되어 {@code MALFORMED_REQUEST}, {@code birthDate} 미래({@code @Past}) 위반·{@code country} 미존재는
 * {@code INVALID_INPUT}으로 처리한다(온보딩 §5는 String 수집·서버 파싱이라 enum 불일치도 INVALID_INPUT인 점과 다름).
 */
public record UpdateProfileRequest(
    String firstName,
    String lastName,
    Gender gender,
    @Past LocalDate birthDate,
    String country,
    Occupation occupation,
    VisaType visaType,
    String lang,
    String name,
    String phoneNumber,
    Boolean marketingAgreed) {}
