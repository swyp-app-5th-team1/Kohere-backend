package com.kohere.listing.application.dto;

/**
 * 프론트가 API 요청에 사용할 안정적인 코드와 현재 사용자 언어의 화면 표시명을 함께 담는다.
 *
 * @param code 필터 요청과 조건 비교에 그대로 사용하는 서버 코드. 예: {@code FEMALE_ONLY}
 * @param label 화면에 보여주는 번역 문자열. 영어 사용자는 {@code Female Only}, 한국어 사용자는 {@code 여성 전용}
 */
public record CodeLabelResponse(String code, String label) {}
