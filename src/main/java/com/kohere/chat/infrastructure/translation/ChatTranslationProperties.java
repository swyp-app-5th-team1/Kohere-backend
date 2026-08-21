package com.kohere.chat.infrastructure.translation;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** {@code app.chat.translation} 환경설정을 타입 안전하게 읽는 설정 객체다. */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.chat.translation")
public class ChatTranslationProperties {

  /** false면 Google을 호출하지 않고 원문+FAILED를 전달한다. */
  private boolean enabled;

  /** Cloud Translation 요청 parent에 사용하는 Google Cloud 프로젝트 ID. */
  private String projectId = "";

  /** 일반 NMT 번역 위치. */
  @NotBlank private String location = "global";

  /** 최초 호출을 포함한 최대 provider 호출 수. */
  @Min(1)
  @Max(5)
  private int maxAttempts = 5;

  /** provider 호출 한 번의 최대 대기 시간. */
  @NotNull private Duration requestTimeout = Duration.ofSeconds(2);

  /** 한 메시지가 번역 최종 상태를 기다리는 전체 시간 예산. */
  @NotNull private Duration deliveryTimeout = Duration.ofSeconds(5);

  /** PROCESSING 작업의 임시 소유 기한. */
  @NotNull private Duration leaseDuration = Duration.ofSeconds(30);

  /** 즉시 신호 유실과 서버 재시작을 복구하는 조회 간격. */
  @NotNull private Duration recoveryInterval = Duration.ofSeconds(60);

  /** 재시도 가능한 오류 사이의 첫 대기 시간. */
  @NotNull private Duration initialBackoff = Duration.ofMillis(200);

  /** Google 호출 전용 thread 개수. */
  @Min(1)
  @Max(32)
  private int workerPoolSize = 4;

  /** 한 번의 복구 조회에서 가져올 최대 작업 수. */
  @Min(1)
  @Max(1000)
  private int recoveryBatchSize = 100;

  /** 번역을 켠 환경에서 프로젝트 ID 누락을 기동 시점에 명확히 알려 준다. */
  @AssertTrue(message = "app.chat.translation.project-id is required when translation is enabled")
  public boolean isProjectConfiguredWhenEnabled() {
    return !enabled || (projectId != null && !projectId.isBlank());
  }
}
