package com.kohere.auth.application;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 임대인 웹(SPA) 클라이언트의 오리진(app.web). 지금 쓰는 곳은 비밀번호 재설정 링크의 base URL 하나뿐이지만, 값의 성질이 "우리가 서비스하는 웹의 주소"라
 * 인증 정책 트리({@code app.auth.*})가 아니라 별도 키에 둔다.
 *
 * <p><b>링크 base URL을 요청 헤더에서 뽑지 않기 위해 존재하는 설정이다.</b> {@code Host}·{@code X-Forwarded-Host}로 조립하면
 * 호출자가 헤더를 위조해 <b>자기 서버로 향하는 재설정 링크를 남의 메일함에 보낼 수 있다</b>(호스트 헤더 포이즈닝 계정 탈취). 그 헤더들이 신뢰 경계가 아니라는 것은
 * {@link com.kohere.common.request.ClientIps}가 이미 같은 이유로 적어 둔 사실이다 — 거기서는 레이트리밋 정밀도만 잃지만 여기서는 계정이
 * 넘어간다.
 *
 * <p><b>미설정이 조용히 넘어간다.</b> {@code @ConfigurationProperties} 바인딩은 해소되지 않은 플레이스홀더를 예외가 아니라 <b>리터럴
 * 문자열</b>로 넣으므로, 프로파일 yml에서 폴백을 지우는 것만으로는 fail-fast가 되지 않는다 — {@code ${APP_WEB_BASE_URL}}이 그대로 박힌
 * 링크가 메일로 나간다. 그래서 {@link com.kohere.auth.infrastructure.PasswordResetLinks}가 기능이 켜져 있을 때 <b>기동
 * 시점에</b> 형식을 검증한다.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.web")
public class WebClientProperties {

  /**
   * 임대인 웹의 오리진(예: {@code https://dev.kohere.app}). 경로·쿼리 없이 스킴 + 호스트까지만 담고, 끝 슬래시는 있어도 없어도 된다(링크
   * 조립에서 접는다).
   *
   * <p>base(application.yml)에서는 <b>비어 있다</b> — 운영에는 임대인 웹이 아직 없어 채울 값이 없고, 잘못된 기본값을 두면 그 주소로 링크가
   * 나간다. 값을 주는 곳은 {@code local}·{@code dev} 프로파일뿐이다.
   */
  private String baseUrl = "";
}
