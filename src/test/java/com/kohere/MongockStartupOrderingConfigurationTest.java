package com.kohere;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/** Mongock이 애플리케이션 데이터 로더보다 먼저 실행되도록 하는 공통 기동 설정을 검증한다. */
class MongockStartupOrderingConfigurationTest {

  /**
   * Mongock의 기본 {@code ApplicationRunner}는 로컬 매물 fixture 러너와 실행 순서를 보장하지 않는다. 반드시 {@code
   * InitializingBean}으로 실행해 구 MongoDB validator를 새 데이터 저장 전에 이행하는지 확인한다.
   */
  @Test
  void mongockRunsBeforeApplicationRunners() throws IOException {
    YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
    List<PropertySource<?>> sources =
        loader.load("application", new ClassPathResource("application.yml"));

    Object configuredRunnerType =
        sources.stream()
            .map(source -> source.getProperty("mongock.runner-type"))
            .filter(value -> value != null)
            .findFirst()
            .orElse(null);

    assertThat(configuredRunnerType).isEqualTo("InitializingBean");
  }
}
