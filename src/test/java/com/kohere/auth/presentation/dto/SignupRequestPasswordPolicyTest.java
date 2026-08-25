package com.kohere.auth.presentation.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 웹 가입 비밀번호 정책의 <b>경계</b>를 고정한다(US-1-11).
 *
 * <p><b>왜 따로 두는가</b> — 문서화 테스트의 400 예시는 {@code "onlyletters"}처럼 <b>문자 종류</b>를 위반하는 값이라 길이 상한을 바꿔도
 * 그대로 통과한다. 즉 상한을 잘못 건드려도 그 테스트는 초록이다. 상한·하한을 실제로 지키는 것은 이 파일뿐이다.
 *
 * <p>검증 대상은 {@code SignupRequest}의 {@code @Pattern} 하나이므로 Spring 컨텍스트 없이 Bean Validation만 띄운다. 다른
 * 필드는 이 테스트의 관심사가 아니라 전부 유효한 값으로 채우고 {@code password} 위반만 센다.
 */
class SignupRequestPasswordPolicyTest {

  private static ValidatorFactory factory;
  private static Validator validator;

  @BeforeAll
  static void setUp() {
    factory = Validation.buildDefaultValidatorFactory();
    validator = factory.getValidator();
  }

  @AfterAll
  static void tearDown() {
    factory.close();
  }

  @DisplayName("정책을 만족하는 비밀번호는 통과한다 — 하한 8자와 상한 20자를 포함한다")
  @ParameterizedTest(name = "[{index}] {0}")
  @ValueSource(
      strings = {
        "Kohere1!", // 하한 경계 8자
        "Kohere12!@", // 10자 (옛 상한)
        "Kohere1!Kohere1!ab@", // 19자
        "Kohere1!Kohere1!abc@" // 상한 경계 20자
      })
  void 정책을_만족하면_통과한다(String password) {
    assertThat(violations(password)).isEmpty();
  }

  @DisplayName("정책 위반은 거절한다 — 길이 경계 밖과 문자 종류 부족, 허용 집합 밖 문자")
  @ParameterizedTest(name = "[{index}] {0}")
  @ValueSource(
      strings = {
        "Kohere!", // 7자 — 하한 미달
        "Kohere1!Kohere1!abcd@", // 21자 — 상한 초과
        "onlyletters", // 숫자·특수문자 없음
        "Kohere12", // 특수문자 없음
        "Kohere!!", // 숫자 없음
        "12345678!", // 영문자 없음
        "Kohere 1!", // 공백 — 허용 집합 밖
        "코히어1!abcd" // 한글 — 허용 집합 밖
      })
  void 정책을_위반하면_거절한다(String password) {
    assertThat(violations(password)).hasSize(1);
  }

  private Set<?> violations(String password) {
    return validator.validateProperty(
        new SignupRequest(
            "김임대",
            "1990-01-01",
            "01012345678",
            "landlord@work.example",
            password,
            true,
            true,
            false),
        "password");
  }
}
