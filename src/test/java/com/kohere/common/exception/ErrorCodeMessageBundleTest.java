package com.kohere.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 모든 {@link ErrorCode}에 메시지 번들 키가 두 벌 다 있는지 본다.
 *
 * <p>번들에 키가 없으면 그 코드는 <b>영어·한국어 어느 쪽으로도 번역되지 않는다</b>. 그런데 이를 강제하는 것이 지금까지 코드 리뷰뿐이라, 실제로 몇 개가 조용히 빠진
 * 채 빌드가 통과해 왔다 — enum과 번들이 서로 다른 파일이고 어느 테스트도 둘을 맞대 보지 않았기 때문이다.
 *
 * <p>새 코드를 추가할 때 번들을 빠뜨리면 <b>여기서 깨진다</b>. 카탈로그 문서까지는 강제하지 못하므로 그쪽은 계속 리뷰가 본다.
 */
class ErrorCodeMessageBundleTest {

  @ParameterizedTest
  @ValueSource(strings = {"messages.properties", "messages_ko.properties"})
  @DisplayName("모든 에러 코드에 메시지 번들 키가 있다")
  void everyErrorCodeHasMessageBundleEntry(String bundle) throws IOException {
    Properties messages = load(bundle);

    List<String> missing =
        Arrays.stream(ErrorCode.values())
            .map(Enum::name)
            .filter(code -> !messages.containsKey(code))
            .toList();

    assertThat(missing)
        .withFailMessage(
            "%s에 키가 없는 에러 코드가 있다: %s — 코드를 추가할 때 번들 2벌과 error-response-guide 카탈로그를 함께 채운다",
            bundle, missing)
        .isEmpty();
  }

  private static Properties load(String bundle) throws IOException {
    Properties properties = new Properties();
    try (InputStream stream =
        ErrorCodeMessageBundleTest.class.getClassLoader().getResourceAsStream(bundle)) {
      assertThat(stream).withFailMessage("%s를 찾지 못했다", bundle).isNotNull();
      properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
    }
    return properties;
  }
}
