package com.kohere.auth.application;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 인증 정책값(refresh·이메일·연락처 인증·임대인 웹). refresh 만료(=Redis TTL 기준, ADR-0011)와 해시 pepper(SHA-256+pepper,
 * ADR-0006), 이메일·연락처 인증번호 해시 pepper를 담는다. 운영 pepper는 환경변수/Secrets Manager로 주입한다.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.auth")
public class AuthProperties {

  private long refreshTtlSeconds = 1209600;
  private String refreshPepper;
  private String emailPepper;
  private String phonePepper;

  /** 임대인 웹(로컬 자격증명) 정책값. */
  private Web web = new Web();

  /** 이메일 찾기용 연락처 인증 남용 방지 한도(app.auth.find-email, #272). */
  private FindEmail findEmail = new FindEmail();

  /**
   * 임대인 웹 로그인 정책(app.auth.web, ADR-0047). 웹 refresh TTL은 앱과 같은 14일이라 별도 키를 두지 않는다.
   *
   * <p>같은 트리의 {@code app.auth.web.refresh-cookie.*}는 여기가 아니라 {@link
   * com.kohere.common.security.RefreshCookieProperties}가 바인딩한다 — 쿠키는 도메인 정책이 아니라 HTTP 전송 수단이라 공유
   * 커널의 보안 패키지에 있다. 이 클래스는 알 필요가 없어 필드를 두지 않으며, 미지정 필드는 무시되므로 바인딩이 충돌하지 않는다.
   */
  @Getter
  @Setter
  public static class Web {

    /** 비밀번호 연속 실패 상한 — 도달하면 계정을 잠근다. 시간 경과 자동 해제도 해제 API도 없다(US-1-12). */
    private int loginMaxFailedAttempts = 10;

    /** 로그인 시도 레이트리밋 한도. */
    private Login login = new Login();

    /** 비밀번호 재설정(= 잠금 해제) 정책값. */
    private PasswordReset passwordReset = new PasswordReset();

    /**
     * 웹 로그인 시도 한도(app.auth.web.login). 위 {@code loginMaxFailedAttempts}가 <b>한 계정을 잠그는</b> 임계값인 것과
     * 달리 이쪽은 <b>요청 자체를 받아 주는</b> 임계값이라 축(IP·이메일)도 창(1시간)도 다르다.
     */
    @Getter
    @Setter
    public static class Login {

      /**
       * 같은 IP에서 1시간에 허용하는 로그인 시도(초과 시 429). 이메일 한도보다 넉넉한 것은 <b>공유 IP</b>(사무실·모바일 NAT) 때문이다 — 한 사무실의
       * 임대인 여럿이 같은 출구 IP를 쓰는 정상 상황을 막지 않을 만큼은 남겨 둔다. 위조 가능한 축이라 정밀도보다 비용 상한이 목적이다.
       */
      private int ipMaxPerHour = 60;

      /**
       * 같은 이메일로 1시간에 허용하는 로그인 시도(초과 시 429). <b>잠금 임계값의 2배</b>로 잡는다 — 정상 사용자가 몇 번 틀리고 다시 맞히는 흐름을 막지
       * 않으면서, 시간당 잠금 가능 계정 수를 실질적으로 묶는다.
       *
       * <p><b>이 값이 잠금 임계값 이하로 내려가면 잠금이 사실상 도달 불가능해진다</b> — 한 시간 창의 이메일 예산을 정확히 전부 써야 상한에 닿고, 그 창에
       * 성공 로그인이 하나라도 섞이면(레이트리밋은 성공·실패를 가리지 않는다) 그 창에서는 잠글 수 없다. 두 값은 함께 움직인다.
       */
      private int emailMaxPerHour = 20;
    }

    /**
     * 비밀번호 재설정 정책(app.auth.web.password-reset, #272). 재설정 확정이 <b>잠금 해제를 겸하므로</b> 독립 해제 API가 없다 —
     * 게이트가 더 약한 해제 경로를 따로 두면 그쪽이 실질 정책이 되기 때문이다(US-1-17).
     */
    @Getter
    @Setter
    public static class PasswordReset {

      /**
       * 기능 활성 여부. <b>기본은 비활성</b>이고 local·dev 프로파일에서만 켠다 — 임대인 웹(SPA)이 운영에 아직 없어 재설정 링크를 보낼 곳이 없기
       * 때문이다. 켜져 있을 때만 {@code app.web.base-url} 형식을 기동 시 검증한다({@link WebClientProperties}) —
       * {@code @ConfigurationProperties}는 미해결 플레이스홀더를 예외 대신 리터럴로 바인딩해서, 값을 비워 두는 것만으로는 fail-fast가
       * 되지 않고 {@code ${APP_WEB_BASE_URL}}이 그대로 박힌 링크가 메일로 나간다.
       *
       * <p>{@code app.auth.test-login.enabled}·{@code app.auth.fixed-verification.enabled}와 같은 관용구다
       * — base에는 운영-위험 기본값을 두지 않고 프로파일에서만 올린다.
       */
      private boolean enabled = false;

      /** 재설정 토큰 만료(초). 메일 도달 지연을 감안하되 유출된 링크가 살아 있는 창이기도 하므로 인증 마커와 같은 30분으로 맞춘다. */
      private long tokenTtlSeconds = 1800;

      /** 재설정 화면의 SPA 경로. 링크는 {@code app.web.base-url + path + "?token="} 으로 조립한다. */
      private String path = "/reset-password";

      /**
       * 같은 이메일로 1시간에 허용하는 링크 발송(초과 시 429). <b>우회할 수 없는 축</b>이라 실제 남용을 묶는 것은 이쪽이다 — 남의 메일함을 채우려면 그
       * 주소를 보내야만 한다.
       *
       * <p><b>로그인 시도 한도({@code app.auth.web.login.email-max-per-hour})와 버킷을 공유하지 않는다.</b> 공유하면 재설정
       * 시도가 로그인 예산을 깎아 잠금 임계값(10)의 2배라는 계약이 깨지고, 잠금이 사실상 도달 불가능해진다.
       */
      private int emailMaxPerHour = 5;

      /** 같은 IP에서 1시간에 허용하는 링크 발송(초과 시 429). 위조 가능한 축이라 비용 가드일 뿐이다. */
      private int ipMaxPerHour = 20;
    }
  }

  /**
   * 이메일 찾기용 연락처 SMS 인증(US-1-16)의 남용 방지 한도. 인증번호 자릿수·TTL·시도 상한·재발송 간격은 가입용과 같은 정책이라 {@code
   * app.phone.*}를 그대로 재사용하고, 여기엔 <b>이 경로에만 필요한 값</b>만 둔다 — permitAll이라 토큰으로 주체를 묶을 수 없어 번호·IP가 유일한
   * 식별자다.
   *
   * <p><b>가입용({@code app.auth.signup-phone.*})과 한도를 나눠 갖는 것이 핵심이다.</b> 예산을 공유하면 사용자가 가입 화면과 이메일 찾기
   * 화면을 오가는 것만으로 서로의 몫을 태워 두세 번 만에 429가 난다 — 한도 5회는 "한 화면에서 한 흐름"을 전제로 잡힌 값이다. 키스페이스가 갈리므로({@code
   * find-email:*} vs {@code signup-phone:*}) 검증 마커도 서로의 게이트를 통과시키지 못한다.
   */
  @Getter
  @Setter
  public static class FindEmail {

    /** 같은 번호로 1시간에 허용하는 발송 시도(초과 시 429). */
    private int phoneMaxPerHour = 5;

    /** 같은 IP에서 1시간에 허용하는 발송 시도(초과 시 429). */
    private int ipMaxPerHour = 20;
  }
}
