package com.kohere.auth.domain;

import java.util.Optional;

/**
 * 비밀번호 재설정 토큰 영속 포트(US-1-17). 구현은 infrastructure에 둔다(의존성 역전, {@link RefreshTokenRepository}와 대칭).
 * 저장소는 Redis이고 키의 수명은 토큰의 수명과 같다 — 만료된 토큰을 청소하는 배치가 필요 없다.
 *
 * <p><b>조회가 둘인 것이 이 포트의 핵심이다.</b> {@link #find}는 읽고 남기고, {@link #consume}은 읽으면서 지운다. 하나로 합칠 수 없는
 * 이유는 두 호출자가 정반대를 요구하기 때문이다 — 사전 확인(§1-9)은 <b>절대 소비하면 안 되고</b>, 확정(§1-10)은 <b>반드시 원자적으로 소비해야</b>
 * 한다.
 *
 * <p><b>{@code find}로 검증한 뒤 {@code delete}로 지우는 조합을 만들지 마라.</b> 그 조합은 같은 링크를 동시에 두 번 눌렀을 때 두 요청이 모두
 * 검증을 통과해 <b>일회용이 아니게 된다</b>. 메일 클라이언트의 링크 프리페치·사용자의 더블클릭·SPA 개발 모드의 이중 렌더링은 드문 사건이 아니라 <b>흔한 기본
 * 동작</b>이고, 그렇게 새어 나간 두 번째 통과는 서버 로그에도 정상 요청으로만 남는다. 그래서 소비는 명령 하나여야 한다.
 */
public interface PasswordResetTokenRepository {

  /** 발급된 토큰 저장. 키 TTL은 토큰의 {@code expiresAt}까지다 — 만료와 소멸이 같은 시점이라 어긋날 여지가 없다. */
  void save(PasswordResetToken token);

  /**
   * <b>소비하지 않는</b> 읽기 전용 조회 — 사전 확인(§1-9) 전용이다.
   *
   * <p>이 메서드가 따로 있는 것이 계약이다. 사용자가 클릭하기 전에 링크가 열리는 경우(메일 프리뷰·기업 메일 게이트웨이의 URL 안전 검사)에도 토큰이 살아남아야 하고,
   * 살아남지 못하면 사용자 눈에는 "메일을 받았는데 언제 눌러도 만료"로 보인다.
   *
   * <p>대가로 이 경로는 토큰 대입 표면이 된다 — 유효한 값을 찾을 때까지 두드릴 수 있다. {@code SecureRandom} 32바이트라 추측이 성립하지 않는다는 데
   * 기대고 별도 레이트리밋을 두지 않는다(§1-9).
   */
  Optional<PasswordResetToken> find(String tokenHash);

  /**
   * <b>원자적</b> 소비 — 읽으면서 같은 명령으로 지운다(Redis {@code GETDEL}). 확정(§1-10)의 첫 단계이며, 여기서 빈 값이 돌아오면
   * 비밀번호·잠금·세션 어느 것도 건드리지 않는다.
   *
   * <p><b>만료 판정은 여기서 하지 않는다.</b> 만료된 토큰이라도 반환하고 지우며, 유효성은 호출부가 {@link
   * PasswordResetToken#isExpired}로 판정한다 — 저장소가 만료를 삼켜 버리면 "없음"과 "만료"가 구분되지 않아 로그에서 원인을 되짚을 수 없다(응답은
   * 어차피 같은 422다).
   *
   * @return 소비된 토큰. 키가 없었으면(미발급·이미 사용됨·TTL 소멸) {@link Optional#empty()}
   */
  Optional<PasswordResetToken> consume(String tokenHash);
}
