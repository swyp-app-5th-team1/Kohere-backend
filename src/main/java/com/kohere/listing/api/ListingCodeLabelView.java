package com.kohere.listing.api;

/**
 * listing 공개 쿼리가 다른 모듈에 전달하는 언어 무관 코드와 사용자 언어 표시명이다.
 *
 * @param code 필터·검증·비즈니스 비교에 사용하는 안정적인 서버 코드
 * @param label 현재 사용자 언어로 화면에 표시할 문자열
 */
public record ListingCodeLabelView(String code, String label) {}
