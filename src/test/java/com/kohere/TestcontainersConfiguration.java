package com.kohere;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 통합 테스트용 공용 엔진 컨테이너(test-strategy §4). MySQL(auth·user, ADR-0005)·Redis(refresh, ADR-0006)를
 * Testcontainers로 띄우고 {@code @ServiceConnection}으로 datasource·redis 설정을 자동 주입한다. MongoDB는 필요한 테스트가
 * 직접 선언한다.
 *
 * <p>실행에 Docker 데몬이 필요하다(로컬·CI 공통).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

  @Bean
  @ServiceConnection
  MySQLContainer<?> mysqlContainer() {
    return new MySQLContainer<>(DockerImageName.parse("mysql:8.0"));
  }

  @Bean
  @ServiceConnection(name = "redis")
  GenericContainer<?> redisContainer() {
    return new GenericContainer<>(DockerImageName.parse("redis:7")).withExposedPorts(6379);
  }
}
