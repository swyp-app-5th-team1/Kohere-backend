package com.kohere.common.security;

import java.time.Instant;

/**
 * 서명과 만료 검증을 통과한 JWT 정보.
 *
 * <p>REST 요청은 지금처럼 {@link AuthPrincipal}만 사용하지만, 장시간 연결되는 STOMP 세션은 연결 뒤에도 토큰 만료 여부를 판단해야 한다. 그래서
 * 인증 주체와 JWT의 실제 {@code exp} 시각을 함께 전달한다. 이 타입에 들어오는 {@code expiresAt}은 토큰 문자열을 단순 해석한 값이 아니라
 * {@link JwtTokenService#verify(String)}가 서명·발급자·만료를 검증한 뒤 꺼낸 값이다.
 *
 * @param principal 토큰의 사용자 식별자와 온보딩 완료 여부
 * @param expiresAt 검증을 통과한 JWT {@code exp} 클레임의 절대 시각
 */
public record JwtVerificationResult(AuthPrincipal principal, Instant expiresAt) {}
