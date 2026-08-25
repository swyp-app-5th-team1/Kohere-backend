package com.kohere.user.domain;

/**
 * 회원 역할. {@code TENANT}·{@code LANDLORD}는 온보딩 제출 엔드포인트로 확정되고 이후 불변이다(세입자 {@code POST
 * /auth/onboarding}, 임대인 {@code POST /auth/landlord/onboarding}). 신규 회원은 기본 {@code TENANT}이며 임대인
 * 온보딩 시 {@code LANDLORD}로 확정한다(V8 DEFAULT 'TENANT'와 정합). domain-model §2 · ADR-0034.
 *
 * <p>세 값은 <b>병존하지 않는다</b> — 한 계정은 셋 중 하나다. 그래서 "임대인이면서 관리자" 같은 조합이 존재할 수 없고, 관리자 승격은 이전 역할을 대체한다.
 */
public enum UserType {

  /** 세입자(외국인 거주 탐색자). */
  TENANT,

  /** 임대인(매물 등록자). */
  LANDLORD,

  /**
   * 관리자(매물 심사자).
   *
   * <p><b>가입 경로가 없다.</b> 운영자가 관리자 전용 계정을 임대인 웹 가입 흐름으로 만든 뒤 {@code UPDATE users SET user_type =
   * 'ADMIN' WHERE id = ? AND status = 'ACTIVE'}로 수동 승격한다. 온보딩으로 확정되는 위 둘과 달리 <b>이 값만 온보딩 이후에
   * 부여</b>되므로, "userType은 온보딩 확정 후 불변"이라는 규칙의 유일한 예외다.
   *
   * <p><b>활동 중인 계정을 승격하지 않는다.</b> 승격하면 이전 역할을 잃어 매물 등록이 막히고({@code requireLandlord}) 임대인 연동 조회에서도
   * 빠진다. 매물을 보유한 임대인을 승격하면 자기 매물을 자기가 심사할 수 있게 된다.
   *
   * <p>관리자는 관리자 전용 API와 계정 관리·공개 조회·학습 콘텐츠만 호출할 수 있다. 세입자·임대인 기능은 각 모듈 서비스의 허용 목록 게이트가 막는다.
   * docs/api/specs/03-listings-favorites.md 「관리자 매물 심사」.
   */
  ADMIN
}
