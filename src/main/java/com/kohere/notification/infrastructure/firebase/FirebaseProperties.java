package com.kohere.notification.infrastructure.firebase;

import jakarta.validation.constraints.AssertTrue;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** {@code app.firebase} 환경설정을 타입 안전하게 읽는 설정 객체다. */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.firebase")
public class FirebaseProperties {

  /** false면 Firebase Admin SDK를 초기화하지 않아 로컬·테스트에서 Google 인증이 필요 없다. */
  private boolean enabled;

  /** FCM 요청을 보낼 Firebase 프로젝트 ID. */
  private String projectId = "";

  /** Firebase를 켠 환경에서 프로젝트 ID 누락을 기동 시점에 명확히 알려 준다. */
  @AssertTrue(message = "app.firebase.project-id is required when Firebase is enabled")
  public boolean isProjectConfiguredWhenEnabled() {
    return !enabled || (projectId != null && !projectId.isBlank());
  }
}
