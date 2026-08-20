package com.kohere.chat.infrastructure.websocket;

import java.security.Principal;

/**
 * JWT로 확인한 사용자를 WebSocket session에서 식별하는 주체다.
 *
 * <p>Spring의 개인 destination은 {@link Principal#getName()}을 라우팅 키로 사용한다. 기존 HTTP용 {@code
 * AuthPrincipal}은 {@code Principal} 구현이 아니므로, 이름이 구현 세부 문자열로 바뀌지 않도록 사용자 번호를 명시적으로 문자열화한다.
 *
 * @param userId JWT subject에서 검증한 실제 {@code users.id}
 */
public record ChatStompPrincipal(long userId) implements Principal {

  /** 사용자 42의 모든 WebSocket 코드가 일관되게 {@code "42"}를 이름으로 사용하게 한다. */
  @Override
  public String getName() {
    return String.valueOf(userId);
  }
}
