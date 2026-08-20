package com.kohere.chat.infrastructure.websocket;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 채팅 WebSocket의 연결·전송 안전값을 {@code application.yml}에서 읽는다.
 *
 * <p>코드에 숫자와 주소를 직접 적으면 로컬·dev·운영 설정을 바꿀 때 코드를 다시 배포해야 한다. 이 클래스가 설정을 한곳에 모아, 같은 코드를 사용하면서 환경변수만 바꿀
 * 수 있게 한다.
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.chat.websocket")
public class ChatWebSocketProperties {

  /**
   * 브라우저 WebSocket 연결을 허용할 정확한 Origin 목록.
   *
   * <p>빈 목록이면 Spring 기본 정책인 같은 Origin만 허용한다. 모바일 네이티브 클라이언트처럼 Origin header가 없는 연결에는 브라우저의 교차
   * Origin 제약을 적용하지 않는다. 운영에서 와일드카드({@code *})는 허용하지 않는다.
   */
  @NotNull private List<String> allowedOrigins = new ArrayList<>();

  /** 통신이 없을 때 서버와 클라이언트가 연결 생존 여부를 확인하는 간격. REST polling 주기가 아니다. */
  @NotNull private Duration heartbeatInterval = Duration.ofSeconds(10);

  /** WebSocket 조각을 모두 합친 STOMP frame 하나의 최대 바이트 수. */
  @Min(1024)
  private int messageSizeLimitBytes = 64 * 1024;

  /** handshake 뒤 첫 STOMP frame이 도착할 때까지 허용하는 시간. CONNECT 없는 익명 소켓을 정리한다. */
  @NotNull private Duration timeToFirstMessage = Duration.ofSeconds(10);

  /** Spring endpoint 설정이 바로 사용할 수 있도록 빈 문자열을 제거한 Origin 배열을 만든다. */
  public String[] allowedOriginsArray() {
    return allowedOrigins.stream()
        .map(String::trim)
        .filter(origin -> !origin.isEmpty())
        .toArray(String[]::new);
  }
}
