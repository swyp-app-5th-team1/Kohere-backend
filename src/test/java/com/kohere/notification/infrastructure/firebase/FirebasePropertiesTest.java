package com.kohere.notification.infrastructure.firebase;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class FirebasePropertiesTest {

  private static ValidatorFactory validatorFactory;
  private static Validator validator;

  @BeforeAll
  static void setUpValidator() {
    validatorFactory = Validation.buildDefaultValidatorFactory();
    validator = validatorFactory.getValidator();
  }

  @AfterAll
  static void closeValidator() {
    validatorFactory.close();
  }

  @Test
  void disabledFirebaseDoesNotRequireProjectId() {
    FirebaseProperties properties = new FirebaseProperties();

    assertThat(validator.validate(properties)).isEmpty();
  }

  @Test
  void enabledFirebaseRequiresProjectId() {
    FirebaseProperties properties = new FirebaseProperties();
    properties.setEnabled(true);

    assertThat(validator.validate(properties))
        .singleElement()
        .extracting(violation -> violation.getMessage())
        .isEqualTo("app.firebase.project-id is required when Firebase is enabled");
  }

  @Test
  void enabledFirebaseAcceptsConfiguredProjectId() {
    FirebaseProperties properties = new FirebaseProperties();
    properties.setEnabled(true);
    properties.setProjectId("kohere-a0b88");

    assertThat(validator.validate(properties)).isEmpty();
  }
}
