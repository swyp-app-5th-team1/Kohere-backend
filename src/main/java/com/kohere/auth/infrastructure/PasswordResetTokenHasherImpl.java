package com.kohere.auth.infrastructure;

import com.kohere.auth.application.AuthProperties;
import com.kohere.auth.domain.PasswordResetTokenHasher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * 재설정 토큰의 단방향 해시({@code SHA-256(token + pepper)}). {@link TokenHasher}와 같은 알고리즘·같은 출력 형식이며 pepper만
 * 다르다.
 *
 * <p><b>왜 새 시크릿을 만들지 않고 {@code app.auth.email-pepper}를 재사용하는가 — 세 가지다.</b>
 *
 * <p>(1) <b>같은 auth 모듈의 인증 비밀이다.</b> pepper가 지키는 것은 "저장소 덤프만으로는 원문을 복원하지 못하게 하는 것"이고, 재설정 토큰과 이메일
 * 인증번호는 그 요구가 완전히 같다. 성질이 다른 비밀을 한 값으로 묶는 것이 아니라 <b>같은 성질의 비밀을 같은 값으로 두는</b> 것이다.
 *
 * <p>(2) <b>키스페이스가 달라 충돌하지 않는다.</b> 이쪽 해시는 {@code pwd-reset:*}의 키가 되고 이메일 인증번호 해시는 {@code
 * email-verify:code:{userId}}의 <b>필드 값</b>이 된다 — 같은 pepper로 만든 해시가 서로의 자리에서 무언가를 열 수 있는 경로가 없다. 하물며
 * 입력 공간도 겹치지 않는다(32바이트 난수 vs 6자리 숫자).
 *
 * <p>(3) <b>신설하면 시크릿 배선이 네 곳으로 늘어난다.</b> pepper 하나를 새로 만들면 base yml · 프로파일 yml · Terraform(SSM
 * 파라미터·태스크 환경변수) · dev 호스트의 {@code .env} 갱신이 함께 따라오고, dev의 {@code refresh-env.sh} 변경은 <b>EC2 재생성
 * 1회</b>를 요구한다. 그 비용이 위 (1)·(2)로 이미 없는 이득을 사려고 치르는 값이다. 배선을 하나 빠뜨리면 어떻게 되는지도 분명하다 — 미해결 플레이스홀더가
 * 리터럴로 바인딩돼 <b>기동은 성공하고</b>, 링크는 발송되며, 확정 단계에서만 해시가 어긋나 전원이 422를 받는다.
 *
 * <p>필요해지면 그때 갈라도 늦지 않다 — 값을 나누는 변경은 하위 호환이 없지만(진행 중인 링크가 전부 죽는다) 그 피해는 "링크를 다시 받는다"가 전부다.
 */
@Component
public class PasswordResetTokenHasherImpl implements PasswordResetTokenHasher {

  private final String pepper;

  public PasswordResetTokenHasherImpl(AuthProperties authProperties) {
    this.pepper = authProperties.getEmailPepper();
  }

  @Override
  public String hash(String token) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hashed = digest.digest((token + pepper).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hashed);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 algorithm unavailable", e);
    }
  }
}
