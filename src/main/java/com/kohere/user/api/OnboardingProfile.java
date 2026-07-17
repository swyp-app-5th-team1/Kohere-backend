package com.kohere.user.api;

import java.time.LocalDate;

/**
 * 온보딩 프로필 입력(모듈 간 전달용). gender·occupation·visaType은 user 소유 enum이라 타입을 공유하지 않고 원시 문자열로
 * 받는다(domain-model §1). {@code country}는 ISO 3166-1 alpha-2 코드, {@code email}은 auth가 인증 완료를 선행 확인한
 * 값이다. {@code lang}은 사용자가 고른 표시 언어(ISO 639-1 소문자)로 **선택**값이며, {@code null}이면 미설정으로 두고(표시 시 en 폴백)
 * 값이 있으면 user 모듈이 지원 목록(en·ko·ja)을 검증한다(#141). 닉네임은 서버가 생성하므로 입력에 없다. 약관 동의는 약관 동의 단계(POST
 * /auth/terms)에서 이미 처리됐다.
 */
public record OnboardingProfile(
    String firstName,
    String lastName,
    String gender,
    LocalDate birthDate,
    String country,
    String occupation,
    String email,
    String visaType,
    String lang) {}
