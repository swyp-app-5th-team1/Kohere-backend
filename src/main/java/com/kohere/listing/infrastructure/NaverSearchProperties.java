package com.kohere.listing.infrastructure;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * 네이버 지역 검색 API의 인증정보와 네트워크 정책을 바인딩한다.
 *
 * <p>Client ID와 Client Secret은 환경변수에서만 주입하며 코드·로그·응답에 노출하지 않는다. URL과 타임아웃은 비밀값이 아니므로 안전한 기본값을 제공하고,
 * 환경별로 조정할 수 있게 둔다.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.naver.search")
public class NaverSearchProperties {

  /** 네이버 비로그인 검색 API의 origin. */
  private String baseUrl = "https://openapi.naver.com";

  /** 네이버 개발자 센터에서 발급한 검색 API Client ID. */
  private String clientId;

  /** 네이버 개발자 센터에서 발급한 검색 API Client Secret. */
  private String clientSecret;

  /** 외부 연결 수립을 기다릴 최대 시간(ms). */
  private long connectTimeoutMillis = 3000;

  /** 연결 후 응답 본문을 기다릴 최대 시간(ms). */
  private long readTimeoutMillis = 5000;

  /**
   * 두 인증값이 모두 실제 호출에 사용할 수 있는지 확인한다.
   *
   * <p>로컬은 키가 없어도 애플리케이션의 다른 기능을 개발할 수 있게 기동을 허용한다. 대신 장소 검색 호출 시 이 값이 {@code false}이면 외부 연동 실패로
   * 명확히 처리한다. dev/prod는 로컬과 달리 설정 파일에 폴백을 두지 않아 환경변수 주입을 전제하지만, 미주입이어도 앱 기동은 막지 않고 장소 검색 호출만 502로
   * 처리한다.
   *
   * @return Client ID와 Client Secret이 모두 공백이 아닐 때 {@code true}
   */
  public boolean isConfigured() {
    return StringUtils.hasText(clientId) && StringUtils.hasText(clientSecret);
  }
}
