package com.kohere.auth.domain;

/**
 * 비밀번호 재설정 토큰의 단방향 해시 포트(US-1-17). 저장소에는 원문이 아닌 {@code SHA-256(token + pepper)}만 보관한다 — refresh
 * 토큰({@link RefreshTokenHasher})과 같은 규칙이고 같은 이유다(ADR-0006).
 *
 * <p><b>등치 조회가 필요하므로 adaptive hash(BCrypt 등)는 쓰지 않는다.</b> 제출된 토큰으로 저장소 키를 곧바로 만들어야 하는데, salt를 품는
 * 해시는 같은 원문에서 매번 다른 값이 나와 키가 성립하지 않는다. 원문 엔트로피가 {@code SecureRandom} 32바이트라 사전 공격 대상이 아니므로 느린 해시가
 * 지킬 것도 없다 — 비밀번호({@link PasswordHasher})와 성질이 정반대다.
 */
public interface PasswordResetTokenHasher {

  /**
   * 토큰 원문의 해시.
   *
   * @param token {@code "pr_"} 접두 불투명 토큰 원문. <b>이 값은 로그에 남기지 않는다</b> — 로그 한 줄이 곧 계정 탈취 수단이다
   */
  String hash(String token);
}
