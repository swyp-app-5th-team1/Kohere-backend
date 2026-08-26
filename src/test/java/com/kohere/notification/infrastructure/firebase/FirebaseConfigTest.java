package com.kohere.notification.infrastructure.firebase;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class FirebaseConfigTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner().withUserConfiguration(FirebaseConfig.class);

  @Test
  void disabledFirebaseDoesNotCreateSdkBeans() {
    contextRunner.run(
        context -> {
          assertThat(context).doesNotHaveBean(FirebaseApp.class);
          assertThat(context).doesNotHaveBean(FirebaseMessaging.class);
        });
  }

  @Test
  void enabledFirebaseCreatesAndClosesNamedApp() {
    FirebaseProperties properties = new FirebaseProperties();
    properties.setEnabled(true);
    properties.setProjectId("kohere-a0b88");

    contextRunner
        .withPropertyValues("app.firebase.enabled=true")
        .withBean(FirebaseProperties.class, () -> properties)
        .withBean("firebaseGoogleCredentials", GoogleCredentials.class, this::testCredentials)
        .run(
            context -> {
              assertThat(context).hasSingleBean(FirebaseApp.class);
              assertThat(context).hasSingleBean(FirebaseMessaging.class);

              FirebaseApp app = context.getBean(FirebaseApp.class);
              assertThat(app.getName()).isEqualTo(FirebaseConfig.FIREBASE_APP_NAME);
              assertThat(app.getOptions().getProjectId()).isEqualTo("kohere-a0b88");
            });

    assertThat(FirebaseApp.getApps())
        .noneMatch(app -> app.getName().equals(FirebaseConfig.FIREBASE_APP_NAME));
  }

  private GoogleCredentials testCredentials() {
    AccessToken accessToken =
        new AccessToken("test-token", Date.from(Instant.now().plusSeconds(3600)));
    return GoogleCredentials.create(accessToken);
  }
}
