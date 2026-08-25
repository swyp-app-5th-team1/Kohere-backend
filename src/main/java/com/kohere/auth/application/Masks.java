package com.kohere.auth.application;

/**
 * 응답·로그에 식별자를 실을 때 쓰는 마스킹 규칙(error-response-guide §6). 순수 함수만 담는 유틸이라 상태도 협력자도 없다.
 *
 * <p><b>왜 한 곳으로 모으는가</b> — 이 규칙은 <b>응답 계약</b>이다. 같은 이메일이 이메일 찾기(US-1-16)와 재설정 토큰 사전 확인(US-1-17)에서 한
 * 글자라도 다르게 나가면 화면마다 다른 값을 보여 주는 셈이고, 그 차이는 RestDocs가 문서화한 예시와도 어긋난다. 사본이 늘어날수록 어긋날 확률만 늘어난다.
 *
 * <p><b>범위는 {@code auth} 안까지다.</b> {@code user} 모듈에도 같은 모양의 사본이 있지만 이번에 함께 끌어올리지 않는다 — 공유 커널로 올리는
 * 것은 모듈 경계를 건드리는 별개 정리이고, 이 변경이 필요로 하는 것은 "auth 안에서 여섯 번째 사본을 만들지 않는 것"뿐이다.
 */
final class Masks {

  private Masks() {}

  /**
   * 이메일 마스킹(예: {@code minhyuk@example.com} → {@code mi***@example.com}). 로컬파트가 두 글자 이하면 한 글자만 남긴다 —
   * 두 글자를 그대로 노출하면 로컬파트가 통째로 드러나는 경우가 생긴다.
   *
   * <p>{@code @}가 없거나 맨 앞에 있는 값은 형식이 깨진 것이라 {@code ***}로 접는다. 여기까지 오는 값은 이미 {@code @Email} 검증을 지났거나
   * DB에서 읽은 값이라 정상 경로에서는 닿지 않지만, 마스킹이 <b>실패해서 원문을 흘리는</b> 일만은 없어야 한다.
   */
  static String maskEmail(String email) {
    if (email == null) {
      return null;
    }
    int at = email.indexOf('@');
    if (at <= 0) {
      return "***";
    }
    String local = email.substring(0, at);
    String domain = email.substring(at);
    String visible = local.length() <= 2 ? local.substring(0, 1) : local.substring(0, 2);
    return visible + "***" + domain;
  }

  /**
   * 연락처 마스킹(예: {@code 01012345678} → {@code 010-****-5678}). 숫자만 남긴 뒤 앞 3자리와 뒤 4자리를 노출한다.
   *
   * <p>숫자가 네 자리에 못 미치면 뒤 4자리를 뗄 수 없으므로 {@code ***}로 접는다.
   */
  static String maskPhone(String phone) {
    if (phone == null) {
      return null;
    }
    String digits = phone.replaceAll("\\D", "");
    if (digits.length() < 4) {
      return "***";
    }
    String prefix = digits.substring(0, Math.min(3, digits.length() - 4));
    String suffix = digits.substring(digits.length() - 4);
    return prefix + "-****-" + suffix;
  }
}
