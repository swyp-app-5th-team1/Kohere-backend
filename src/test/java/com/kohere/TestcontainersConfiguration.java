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

  /**
   * MySQL 컨테이너.
   *
   * <p><b>네이티브 AIO를 끈다.</b> 이 빈은 정적 필드가 아니라 {@code @Bean}이라 <b>Spring 컨텍스트마다 새 컨테이너가 뜬다</b> — 자기
   * MongoDB 컨테이너를 선언하는 통합·문서화 테스트가 각자 별도 컨텍스트를 만들기 때문이다. 그런데 InnoDB는 기동할 때 커널의 AIO 컨텍스트를 미리 잡고, 리눅스
   * 기본 {@code fs.aio-max-nr}(65536)는 그 인스턴스 수를 감당하지 못한다. 한도를 넘기면 {@code io_setup() failed with
   * EAGAIN} → {@code Cannot initialize AIO sub-system}으로 컨테이너가 종료 코드 1로 죽고, 그 컨텍스트를 쓰는 테스트가 통째로
   * 실패한다.
   *
   * <p>스위트가 커질수록 재발하고 <b>단독 실행에서는 재현되지 않아</b> 원인을 찾기 어렵다. 테스트용 MySQL은 성능이 목적이 아니므로 AIO를 끄는 편이 낫다.
   */
  @Bean
  @ServiceConnection
  MySQLContainer<?> mysqlContainer() {
    return new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
        .withCommand("--innodb-use-native-aio=0");
  }

  @Bean
  @ServiceConnection(name = "redis")
  GenericContainer<?> redisContainer() {
    return new GenericContainer<>(DockerImageName.parse("redis:7")).withExposedPorts(6379);
  }
}
