package com.kohere.auth.infrastructure;

import com.kohere.auth.application.AuthProperties;
import com.kohere.auth.application.WebClientProperties;
import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.net.URISyntaxException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 비밀번호 재설정 링크 조립기(스펙 §1-8). {@code app.web.base-url} + {@code app.auth.web.password-reset.path} +
 * {@code ?token=…} 세 조각을 하나로 붙이고, 기능이 켜져 있으면 <b>기동 시점에</b> base URL의 형식을 검증한다.
 *
 * <p><b>요청 헤더로 조립하지 않는다 — 이것이 이 클래스가 존재하는 이유다.</b> {@code Host}·{@code X-Forwarded-Host}는 호출자가 채워
 * 보낼 수 있는 값이다. 그것으로 링크를 만들면 공격자는 <b>자기 도메인이 박힌 재설정 링크를 피해자의 메일함으로 보낼 수 있고</b>(피해자 이메일로 §1-8을 부르면서
 * 헤더만 바꾸면 된다), 피해자가 클릭하는 순간 토큰 원문이 공격자 서버의 액세스 로그에 남는다. 그 토큰으로 §1-10을 부르면 <b>계정이 통째로 넘어간다</b> — 메일
 * 본문은 우리 서비스가 보낸 진짜 메일이라 사용자가 의심할 단서가 없다. 그래서 base URL은 <b>설정값에서만</b> 온다. 이 헤더들이 신뢰 경계가 아니라는 것은
 * {@link com.kohere.common.request.ClientIps}가 이미 같은 이유로 적어 둔 사실이고, 거기서는 레이트리밋 정밀도만 잃지만 여기서는 계정이
 * 넘어간다.
 *
 * <p><b>기동 검증은 기능이 켜졌을 때만 한다.</b> base(운영) 설정은 {@code app.web.base-url}이 비어 있고 그게 정상이다 — 임대인 웹이 운영에
 * 없어 채울 값이 없다. 토글이 꺼진 프로파일에서까지 값을 요구하면 재설정과 무관한 배포가 기동에 실패한다.
 *
 * <p><b>반대로 켜져 있는데 값이 이상하면 기동을 막는다.</b> {@code @ConfigurationProperties} 바인딩은 해소되지 않은 플레이스홀더를 예외가
 * 아니라 <b>리터럴 문자열</b>로 넣으므로({@code ${APP_WEB_BASE_URL}}이 값 자체가 된다), 값을 비워 두는 것만으로는 fail-fast가 되지
 * 않는다. 그대로 두면 <b>깨진 링크가 담긴 메일이 조용히 나가고</b>, 그 사실은 사용자가 클릭한 뒤에야 드러난다 — 서버 로그에는 정상 발송으로만 남는다. 배포가 뜨지
 * 않는 편이 낫다.
 */
@Component
@RequiredArgsConstructor
public class PasswordResetLinks {

  private static final String TOKEN_QUERY = "?token=";

  private final WebClientProperties webClientProperties;
  private final AuthProperties authProperties;

  /**
   * base URL 형식 검증 — 스킴과 호스트가 모두 있어야 한다. {@code java.net.URI}로 파싱해 판정하므로 플레이스홀더 리터럴({@code
   * ${APP_WEB_BASE_URL}})·경로만 적힌 값·빈 문자열이 전부 걸린다.
   *
   * <p>스킴을 {@code https}로 강제하지는 않는다 — local 프로파일이 {@code http://localhost:5173}을 쓴다.
   */
  @PostConstruct
  void validateBaseUrl() {
    if (!authProperties.getWeb().getPasswordReset().isEnabled()) {
      return;
    }
    String baseUrl = webClientProperties.getBaseUrl();
    if (!StringUtils.hasText(baseUrl) || !hasSchemeAndHost(baseUrl)) {
      throw new IllegalStateException(
          "app.auth.web.password-reset.enabled=true 이면 app.web.base-url 은 스킴과 호스트를 갖춘 절대 URL이어야 한다"
              + "(현재: \""
              + baseUrl
              + "\"). 이 값이 잘못되면 깨진 재설정 링크가 담긴 메일이 조용히 발송된다.");
    }
  }

  /**
   * 토큰 원문을 담은 재설정 링크. 사용자가 메일에서 클릭해 도착하는 곳은 프런트 SPA 페이지다.
   *
   * <p><b>끝 슬래시를 접는다.</b> {@code https://x.app/} + {@code /reset-password}를 그냥 이으면 {@code
   * https://x.app//reset-password}가 된다 — 브라우저는 열지만 SPA 라우터가 매칭에 실패해 404 화면을 띄우거나, 라우터가 통과시켜도 절대 경로
   * 자산 요청이 어긋난다. 설정값의 끝 슬래시 유무는 사람이 매번 맞출 값이 아니라 코드가 흡수할 값이다.
   *
   * <p>토큰은 URL 인코딩하지 않는다 — {@code Base64.getUrlEncoder()}의 출력 알파벳({@code A-Za-z0-9-_})과 {@code
   * "pr_"} 접두는 전부 쿼리에서 안전한 문자라 인코딩이 값을 바꾸지 않는다.
   */
  public String build(String rawToken) {
    String baseUrl = trimTrailingSlash(webClientProperties.getBaseUrl());
    String path = authProperties.getWeb().getPasswordReset().getPath();
    return baseUrl + ensureLeadingSlash(trimTrailingSlash(path)) + TOKEN_QUERY + rawToken;
  }

  private static boolean hasSchemeAndHost(String value) {
    try {
      URI uri = new URI(value);
      return StringUtils.hasText(uri.getScheme()) && StringUtils.hasText(uri.getHost());
    } catch (URISyntaxException e) {
      return false;
    }
  }

  private static String trimTrailingSlash(String value) {
    if (value == null) {
      return "";
    }
    String trimmed = value.trim();
    return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
  }

  private static String ensureLeadingSlash(String path) {
    if (!StringUtils.hasText(path)) {
      return "";
    }
    return path.startsWith("/") ? path : "/" + path;
  }
}
