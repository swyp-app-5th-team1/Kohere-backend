package com.kohere.notification.infrastructure.firebase;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 기존 ADC/WIF 인증으로 Firebase Admin SDK를 초기화한다. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.firebase", name = "enabled", havingValue = "true")
public class FirebaseConfig {

  static final String FIREBASE_APP_NAME = "kohere-fcm";

  /**
   * Google SDK의 Application Default Credentials를 읽는다.
   *
   * <p>dev에서는 {@code GOOGLE_APPLICATION_CREDENTIALS}가 가리키는 WIF 설정을 사용한다. 서비스 계정 개인키를 다운로드하거나 번역용 인증
   * 설정을 변경하지 않는다.
   */
  @Bean("firebaseGoogleCredentials")
  @ConditionalOnMissingBean(name = "firebaseGoogleCredentials")
  GoogleCredentials firebaseGoogleCredentials() throws IOException {
    return GoogleCredentials.getApplicationDefault();
  }

  /** 프로젝트 ID와 ADC 자격증명으로 이름 있는 Firebase 앱을 한 번 생성한다. */
  @Bean(destroyMethod = "delete")
  FirebaseApp firebaseApp(
      FirebaseProperties properties,
      @Qualifier("firebaseGoogleCredentials") GoogleCredentials credentials) {
    FirebaseOptions options =
        FirebaseOptions.builder()
            .setCredentials(credentials)
            .setProjectId(properties.getProjectId())
            .build();
    return FirebaseApp.initializeApp(options, FIREBASE_APP_NAME);
  }

  /** 이후 FCM 발송 어댑터가 주입받아 사용할 서버 SDK 진입점이다. */
  @Bean
  FirebaseMessaging firebaseMessaging(FirebaseApp firebaseApp) {
    return FirebaseMessaging.getInstance(firebaseApp);
  }
}
