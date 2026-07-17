package com.kohere.user.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 온보딩 완료 직후 반환하는 회원 프로필(모듈 간 전달용). gender·occupation·visaType·status는 user 소유 enum이라 원시 문자열로 노출해 내부
 * 타입을 공유하지 않는다(domain-model §1·§2).
 *
 * <p>세입자·임대인 공용 뷰다 — {@code userType}에 따라 필드가 갈린다. 세입자는 {@code firstName}·{@code lastName}과
 * 성별·국적·직업·비자정보를, 임대인은 단일 {@code name}(전체 이름)·마스킹된 {@code phoneNumber}를 채우고 나머지는 {@code null}로 둔다.
 * {@code null} 필드는 응답에서 생략한다({@link JsonInclude.Include#NON_NULL}) — 세입자 응답엔 {@code name}·{@code
 * phoneNumber}가, 임대인 응답엔 세입자 전용 필드가 나타나지 않는다.
 *
 * <p>{@code country}는 ISO 코드, {@code countryName}·{@code countryFlag}는 서버가 {@code countries} 참조로
 * resolve한 표시값이며 {@code countryFlag}는 국기 이미지 URL(flagcdn.com SVG)이다(저장은 코드만).
 * docs/api/specs/01-auth-onboarding.md §5(세입자)·§5-2(임대인) onboarding 응답의 {@code user}·시퀀스 US-1-2와
 * 정합.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserProfileView(
    long id,
    String firstName,
    String lastName,
    String name,
    String nickname,
    String gender,
    LocalDate birthDate,
    String country,
    String countryName,
    String countryFlag,
    String lang,
    String occupation,
    String email,
    String visaType,
    String phoneNumber,
    String userType,
    String status,
    boolean marketingAgreed,
    Instant createdAt) {}
