package com.kohere.chat.application;

import com.kohere.chat.domain.AppUserOnlyChatException;
import com.kohere.user.api.UserAccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 세입자·임대인만 통과시키는 <b>허용 목록</b> 게이트다(US-3-7).
 *
 * <p>관리자({@code userType=ADMIN})는 관리자 전용 API만 호출할 수 있다. 그런데 대부분의 사용자용 API는 {@code hasRole("USER")}만
 * 보고 {@code userType}을 검사하지 않으므로, 관리자도 {@code ACTIVE}라 그대로 통과한다. 그 구멍을 이 게이트가 막는다.
 *
 * <p><b>관리자를 콕 집어 거부하지 않는다.</b> "세입자 또는 임대인만 통과"라고 쓰면 이 모듈이 {@code ADMIN}이라는 개념을 몰라도 되고, 나중에 회원 유형이
 * 늘어도 <b>자동으로 거부</b>된다(fail-closed). 기존 {@code requireLandlord}·{@code requireTenant}와 같은 모양이다.
 *
 * <p><b>새 사용자용 API를 추가하면 이 게이트를 함께 붙인다.</b> 인가가 보안 매처가 아니라 서비스 코드에 있으므로 빠뜨리면 관리자가 통과한다.
 */
// 네 모듈이 같은 이름의 클래스를 각자 갖는다. 빈 이름을 모듈별로 못박지 않으면 Spring 기본 이름(appUserGuard)이
// 충돌해 컨텍스트가 뜨지 않는다 — 타입은 서로 달라 주입은 문제없지만 이름은 전역이다.
@Component("chatAppUserGuard")
@RequiredArgsConstructor
public class AppUserGuard {

  private static final String USER_TYPE_TENANT = "TENANT";
  private static final String USER_TYPE_LANDLORD = "LANDLORD";

  private final UserAccountService userAccountService;

  /**
   * 세입자 또는 임대인이 아니면 거부한다.
   *
   * @param userId 요청 주체
   * @throws AppUserOnlyChatException 세입자·임대인이 아닌 경우(관리자 등)
   */
  public void requireAppUser(long userId) {
    String userType = userAccountService.getUserType(userId);
    if (!USER_TYPE_TENANT.equals(userType) && !USER_TYPE_LANDLORD.equals(userType)) {
      throw new AppUserOnlyChatException();
    }
  }
}
