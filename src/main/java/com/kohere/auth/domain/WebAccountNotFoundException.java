package com.kohere.auth.domain;

import com.kohere.common.exception.BusinessException;
import com.kohere.common.exception.ErrorCode;

/**
 * 이메일 찾기(§1-7)에서 인증된 번호로 <b>웹 자격증명이 붙은 계정</b>을 특정하지 못함. 전역 핸들러가 404 {@code
 * AUTH_WEB_ACCOUNT_NOT_FOUND}로 변환한다. 웹 자격증명이 이미 있어 붙일 수 없는 {@link
 * WebAccountAlreadyExistsException}(409)과 대칭 위치다.
 *
 * <p><b>계정 미존재와 이름 불일치를 이 하나로 수렴시킨다.</b> 둘을 가르면 남는 것은 <b>이름 오라클</b>이다 — 자기 번호로 인증한 사람이 그 번호에 붙은 계정
 * 명의자의 이름을 한 후보씩 확인할 수 있게 된다(번호는 손에 있지만 명의자를 모르는 경우 — 중고 번호·가족 명의). <b>계정 존재까지는 드러내되 명의자 이름은 드러내지
 * 않는다</b>가 경계선이다.
 *
 * <p>존재를 드러내는 404 자체는 여기서만 허용된다 — 이 경로는 SMS 인증 마커 뒤에 있어 조회 가능한 번호가 자기 번호 하나로 닫혀 있다. 선행 게이트가 없는 재설정
 * 링크 발송(§1-8)이 미가입 이메일에도 같은 200을 주는 것과 반대 선택이며, 갈리는 지점은 <b>선행 게이트의 유무</b>다.
 */
public class WebAccountNotFoundException extends BusinessException {

  public WebAccountNotFoundException() {
    super(ErrorCode.AUTH_WEB_ACCOUNT_NOT_FOUND);
  }
}
